package com.nihyli.cloverpromotions

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import com.nihyli.cloverpromotions.ui.MainViewModel
import com.nihyli.cloverpromotions.ui.RulesScreen

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                RulesScreen(viewModel)
            }
        }
    }
}
