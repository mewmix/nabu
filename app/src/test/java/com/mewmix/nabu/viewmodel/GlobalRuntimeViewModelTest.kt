package com.mewmix.nabu.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GlobalRuntimeViewModelTest {

    @Test
    fun testInitialStateIsLoading() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = GlobalRuntimeViewModel(app)
        
        // Initial state should be Loading
        assertTrue(viewModel.modelState.value is ModelState.Loading)
    }
}
