package io.github.ylc.messagescomplication

import android.app.PendingIntent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

class UnreadMessagesComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val unreadCount = MessagesNotificationListener.getStoredCount(applicationContext)
        return buildComplicationData(
            type = request.complicationType,
            count = unreadCount,
            tapAction = createLaunchMessagesIntent()
        )
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return buildComplicationData(type = type, count = 3, tapAction = null)
    }

    private fun buildComplicationData(
        type: ComplicationType,
        count: Int,
        tapAction: PendingIntent?
    ): ComplicationData? {
        val icon = MonochromaticImage.Builder(
            Icon.createWithResource(applicationContext, android.R.drawable.sym_action_chat)
        ).build()

        val contentDesc = PlainComplicationText.Builder("$count unread messages").build()

        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(count.toString()).build(),
                    contentDescription = contentDesc
                )
                    .setMonochromaticImage(icon)
                    .setTapAction(tapAction)
                    .build()
            }

            ComplicationType.LONG_TEXT -> {
                val label = if (count == 1) "1 unread message" else "$count unread messages"
                LongTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(label).build(),
                    contentDescription = contentDesc
                )
                    .setMonochromaticImage(icon)
                    .setTapAction(tapAction)
                    .build()
            }

            else -> null
        }
    }

    private fun createLaunchMessagesIntent(): PendingIntent? {
        val launchIntent = packageManager
            .getLaunchIntentForPackage(MessagesNotificationListener.MESSAGES_PKG)
            ?: return null

        return PendingIntent.getActivity(
            applicationContext,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
