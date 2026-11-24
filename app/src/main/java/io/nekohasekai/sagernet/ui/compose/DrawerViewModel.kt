package io.nekohasekai.sagernet.ui.compose

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import io.nekohasekai.sagernet.R

class DrawerViewModel : ViewModel() {
    val selection = MutableStateFlow(R.id.nav_configuration)
    val trafficVisible = MutableStateFlow(true)
}
