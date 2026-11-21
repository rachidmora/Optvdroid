package com.atvplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.atvplayer.MainViewModel
import com.atvplayer.data.BoxConfig

@Composable
fun App(vm: MainViewModel) {
    var showSetup by remember { mutableStateOf(vm.config.host.isBlank()) }

    PlayerScreen(vm, onOpenSettings = { showSetup = true })

    if (showSetup) SetupDialog(vm.config, onDismiss = { showSetup = false }) {
        vm.saveConfig(it); showSetup = false
    }
}

@Composable
private fun SetupDialog(cfg: BoxConfig, onDismiss: () -> Unit, onSave: (BoxConfig) -> Unit) {
    var host by remember { mutableStateOf(cfg.host) }
    var http by remember { mutableStateOf(cfg.httpPort.toString()) }
    var stream by remember { mutableStateOf(cfg.streamPort.toString()) }
    var user by remember { mutableStateOf(cfg.user) }
    var pass by remember { mutableStateOf(cfg.pass) }
    var tmdb by remember { mutableStateOf(cfg.tmdbKey) }
    val num = KeyboardOptions(keyboardType = KeyboardType.Number)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Pal.Surface,
        title = { Text("Récepteur OpenWebIF") },
        text = {
            Column {
                OutlinedTextField(host, { host = it }, label = { Text("Adresse IP (ex: 192.168.1.20)") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(http, { http = it }, label = { Text("Port web") }, singleLine = true, keyboardOptions = num)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(stream, { stream = it }, label = { Text("Port streaming") }, singleLine = true, keyboardOptions = num)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(user, { user = it }, label = { Text("Utilisateur (optionnel)") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("Mot de passe") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(tmdb, { tmdb = it }, label = { Text("Clé TMDB pour les posters (optionnel)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(BoxConfig(host.trim(), http.toIntOrNull() ?: 80, stream.toIntOrNull() ?: 8001, user.trim(), pass, tmdb.trim()))
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
