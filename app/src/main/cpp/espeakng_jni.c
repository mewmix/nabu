#include <jni.h>
#include <string.h>
#include <pthread.h>
#include "espeak-ng/speak_lib.h"

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_inited = 0;

static void ensure_init() {
    if (g_inited) return;
    int samplerate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, NULL, 0);
    (void)samplerate;
    espeak_SetParameter(espeakSSML, 1, 0);
    g_inited = 1;
}

JNIEXPORT jstring JNICALL
Java_com_mewmix_nabu_core_tts_EspeakNgAdapter_nativeG2pToIpa(
        JNIEnv* env, jobject thiz, jstring jtext, jstring jlang) {
    const char* text = (*env)->GetStringUTFChars(env, jtext, 0);
    const char* lang = (*env)->GetStringUTFChars(env, jlang, 0);

    pthread_mutex_lock(&g_lock);
    ensure_init();

    espeak_VOICE v = {0};
    v.languages = (char*)lang;
    v.name = NULL;
    v.variant = 0;
    v.age = 0;
    espeak_SetVoiceByProperties(&v);

    espeak_SetPhonemeTrace(0x20 | 0x08 | 0x01);

    char* out = espeak_TextToPhonemes((const void*)text, espeakCHARS_UTF8, 0x20);

    jstring result = NULL;
    if (out && strlen(out) > 0) {
        result = (*env)->NewStringUTF(env, out);
    }

    pthread_mutex_unlock(&g_lock);
    (*env)->ReleaseStringUTFChars(env, jtext, text);
    (*env)->ReleaseStringUTFChars(env, jlang, lang);
    return result;
}
