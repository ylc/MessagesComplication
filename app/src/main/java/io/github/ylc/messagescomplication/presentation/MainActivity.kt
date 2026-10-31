package io.github.ylc.messagescomplication.presentation

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import io.github.ylc.messagescomplication.MessagesNotificationListener
import io.github.ylc.messagescomplication.presentation.theme.MessagesComplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WearApp()
        }
    }
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    return NotificationManagerCompat.getEnabledListenerPackages(context)
        .contains(context.packageName)
}

@Composable
fun WearApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember { mutableStateOf(isNotificationListenerEnabled(context)) }
    var unreadCount by remember { mutableIntStateOf(MessagesNotificationListener.getStoredCount(context)) }
    var countIndividual by remember {
        mutableStateOf(MessagesNotificationListener.isCountingIndividualMessages(context))
    }

    DisposableEffect(lifecycleOwner, context) {
        val prefs = context.getSharedPreferences(
            MessagesNotificationListener.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            unreadCount = MessagesNotificationListener.getStoredCount(context)
            countIndividual = MessagesNotificationListener.isCountingIndividualMessages(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = isNotificationListenerEnabled(context)
                unreadCount = MessagesNotificationListener.getStoredCount(context)
                countIndividual = MessagesNotificationListener.isCountingIndividualMessages(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    MessagesComplicationTheme {
        AppScaffold {
            val listState = rememberTransformingLazyColumnState()
            val transformationSpec = rememberTransformationSpec()
            ScreenScaffold(scrollState = listState) { contentPadding ->
                TransformingLazyColumn(contentPadding = contentPadding, state = listState) {
                    item {
                        ListHeader(
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                        ) {
                            Text(
                                text = "Messages Badge",
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    item {
                        Button(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            },
                            colors = if (hasPermission) {
                                ButtonDefaults.filledTonalButtonColors()
                            } else {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                        ) {
                            Text(
                                text = if (hasPermission) {
                                    "Listener: Active ($unreadCount unread)"
                                } else {
                                    "Grant Notification Access"
                                }
                            )
                        }
                    }

                    item {
                        Button(
                            onClick = {
                                val nextValue = !countIndividual
                                countIndividual = nextValue
                                MessagesNotificationListener.setCountingIndividualMessages(
                                    context,
                                    nextValue
                                )
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                        ) {
                            Text(
                                text = if (countIndividual) {
                                    "Mode: Total Messages"
                                } else {
                                    "Mode: Conversations"
                                }
                            )
                        }
                    }

                    item {
                        Button(
                            onClick = {
                                val launchIntent = context.packageManager
                                    .getLaunchIntentForPackage(MessagesNotificationListener.MESSAGES_PKG)
                                if (launchIntent != null) {
                                    context.startActivity(launchIntent)
                                }
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                        ) {
                            Text("Open Google Messages")
                        }
                    }
                }
            }
        }
    }
}
