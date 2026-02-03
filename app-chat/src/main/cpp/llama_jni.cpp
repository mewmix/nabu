#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <thread>
#include <algorithm>
#include <ctime>

#include "llama.h"

namespace {

constexpr const char * kLogTag = "LlamaJni";
constexpr int kDefaultPredictTokens = 256;
constexpr int64_t kMaxGenerateMs = 30000;
constexpr int kDefaultCtx = 1024;
constexpr int kDefaultBatch = 64;
constexpr int kMaxThreads = 2;

struct LlamaInstance {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    std::mutex mutex;
};

std::mutex g_backend_mutex;
bool g_backend_ready = false;

int get_thread_count() {
    unsigned int count = std::thread::hardware_concurrency();
    if (count == 0) {
        return 1;
    }
    return static_cast<int>(std::max(1u, std::min(count, static_cast<unsigned int>(kMaxThreads))));
}

void ensure_backend_ready() {
    std::lock_guard<std::mutex> lock(g_backend_mutex);
    if (!g_backend_ready) {
        llama_backend_init();
        g_backend_ready = true;
    }
}

std::string detokenize_token(const llama_vocab * vocab, llama_token token) {
    char buffer[256];
    const int n = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, true);
    if (n <= 0) {
        return {};
    }
    return std::string(buffer, static_cast<size_t>(n));
}

int64_t now_ms() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_mewmix_nabu_chat_LlamaBridge_init(JNIEnv * env, jobject /*thiz*/, jstring model_path) {
    if (model_path == nullptr) {
        return 0L;
    }

    ensure_backend_ready();

    const char * path_chars = env->GetStringUTFChars(model_path, nullptr);
    if (path_chars == nullptr) {
        return 0L;
    }

    llama_model_params mparams = llama_model_default_params();
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = kDefaultCtx;
    cparams.n_batch = kDefaultBatch;
    cparams.n_threads = get_thread_count();
    cparams.n_threads_batch = cparams.n_threads;

    llama_model * model = llama_model_load_from_file(path_chars, mparams);
    env->ReleaseStringUTFChars(model_path, path_chars);

    if (model == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to load model");
        return 0L;
    }

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to create context");
        llama_model_free(model);
        return 0L;
    }

    auto * instance = new LlamaInstance();
    instance->model = model;
    instance->ctx = ctx;
    return reinterpret_cast<jlong>(instance);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mewmix_nabu_chat_LlamaBridge_close(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * instance = reinterpret_cast<LlamaInstance *>(handle);
    if (instance == nullptr) {
        return;
    }

    {
        std::lock_guard<std::mutex> lock(instance->mutex);
        if (instance->ctx != nullptr) {
            llama_free(instance->ctx);
            instance->ctx = nullptr;
        }
        if (instance->model != nullptr) {
            llama_model_free(instance->model);
            instance->model = nullptr;
        }
    }

    delete instance;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mewmix_nabu_chat_LlamaBridge_generate(JNIEnv * env, jobject /*thiz*/, jlong handle, jstring prompt) {
    auto * instance = reinterpret_cast<LlamaInstance *>(handle);
    if (instance == nullptr || instance->ctx == nullptr || instance->model == nullptr || prompt == nullptr) {
        return env->NewStringUTF("");
    }

    const char * prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    if (prompt_chars == nullptr) {
        return env->NewStringUTF("");
    }

    std::string prompt_str(prompt_chars);
    env->ReleaseStringUTFChars(prompt, prompt_chars);

    std::lock_guard<std::mutex> lock(instance->mutex);

    llama_context * ctx = instance->ctx;
    llama_model * model = instance->model;
    const llama_vocab * vocab = llama_model_get_vocab(model);

    llama_memory_clear(llama_get_memory(ctx), true);

    const int n_prompt_tokens = -llama_tokenize(vocab, prompt_str.c_str(), prompt_str.size(), nullptr, 0, true, true);
    if (n_prompt_tokens <= 0) {
        return env->NewStringUTF("");
    }
    __android_log_print(ANDROID_LOG_DEBUG, kLogTag, "Prompt tokens: %d", n_prompt_tokens);

    std::vector<llama_token> prompt_tokens(static_cast<size_t>(n_prompt_tokens));
    if (llama_tokenize(vocab, prompt_str.c_str(), prompt_str.size(), prompt_tokens.data(), prompt_tokens.size(), true, true) < 0) {
        return env->NewStringUTF("");
    }

    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), prompt_tokens.size());

    if (llama_model_has_encoder(model)) {
        if (llama_encode(ctx, batch) != 0) {
            return env->NewStringUTF("");
        }
        llama_token decoder_start_token_id = llama_model_decoder_start_token(model);
        if (decoder_start_token_id == LLAMA_TOKEN_NULL) {
            decoder_start_token_id = llama_vocab_bos(vocab);
        }
        batch = llama_batch_get_one(&decoder_start_token_id, 1);
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    std::string output;
    output.reserve(1024);

    int n_pos = 0;
    int64_t t_start = now_ms();
    int64_t t_first = -1;
    for (int i = 0; i < kDefaultPredictTokens; ++i) {
        if (now_ms() - t_start > kMaxGenerateMs) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "Generation timed out after %lld ms", static_cast<long long>(now_ms() - t_start));
            break;
        }
        if (llama_decode(ctx, batch) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "llama_decode failed at token %d", i);
            break;
        }

        n_pos += batch.n_tokens;
        llama_token token = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            __android_log_print(ANDROID_LOG_DEBUG, kLogTag, "Reached EOG at token %d", i);
            break;
        }

        if (t_first < 0) {
            t_first = now_ms();
            __android_log_print(ANDROID_LOG_DEBUG, kLogTag, "Time to first token: %lld ms", static_cast<long long>(t_first - t_start));
        }

        output += detokenize_token(vocab, token);
        if ((i + 1) % 16 == 0) {
            __android_log_print(ANDROID_LOG_DEBUG, kLogTag, "Generated %d tokens", i + 1);
        }
        batch = llama_batch_get_one(&token, 1);
    }

    llama_sampler_free(sampler);
    __android_log_print(ANDROID_LOG_DEBUG, kLogTag, "Generation complete, output bytes=%zu", output.size());

    return env->NewStringUTF(output.c_str());
}
