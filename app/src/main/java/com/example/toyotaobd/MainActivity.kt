package com.example.toyotaobd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.toyotaobd.ui.ObdApp
import com.example.toyotaobd.ui.rememberObdViewModel
import com.example.toyotaobd.ui.theme.ToyotaObdTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ToyotaObdTheme {
                ObdApp(rememberObdViewModel((application as ObdApplication).deps))
            }
        }
    }
}
