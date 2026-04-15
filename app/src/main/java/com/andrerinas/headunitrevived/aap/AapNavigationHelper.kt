package com.andrerinas.headunitrevived.aap

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.protocol.proto.NavigationStatus
import com.andrerinas.headunitrevived.contract.NavigationUpdateIntent
import com.andrerinas.headunitrevived.utils.AppLog

class AapNavigationHelper(
    private val context: Context
) {
    data class TimedMessage<T>(
        val payload: T,
        val updatedAtElapsedRealtimeMs: Long
    )

    data class NavigationSnapshot(
        var clusterStatus: TimedMessage<NavigationStatus.NavigationClusterStatus>? = null,
        @Deprecated(
            message = "This message may be not send by device",
            level = DeprecationLevel.WARNING
        )
        var nextTurnDetail: TimedMessage<NavigationStatus.NextTurnDetail>? = null,
        @Deprecated(
            message = "This message may be not send by device",
            level = DeprecationLevel.WARNING
        )
        var nextTurnDistance: TimedMessage<NavigationStatus.NextTurnDistanceEvent>? = null,
        var navigationState: TimedMessage<NavigationStatus.NavigationState>? = null,
        var currentPosition: TimedMessage<NavigationStatus.NavigationCurrentPosition>? = null,
        var currentStreet: TimedMessage<String>? = null
    )

    private data class FullNavigationMessage(
        val distanceMeters: Int?,
        val timeSeconds: Int?,
        val road: String,
        val nextEventType: Int,
        val actionText: String,
        val turnSide: Int?,
        val turnNumber: Int?,
        val turnAngle: Int?
    )

    fun nowElapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    fun sendFullNavigationBroadcast(snapshot: NavigationSnapshot, navEventType: Int) {
        val prepared = prepareFullNavigationMessage(snapshot, navEventType)
        val intent = NavigationUpdateIntent(
            distanceMeters = prepared.distanceMeters,
            timeSeconds = prepared.timeSeconds,
            road = prepared.road,
            nextEventType = prepared.nextEventType,
            actionText = prepared.actionText,
            turnSide = prepared.turnSide,
            turnNumber = prepared.turnNumber,
            turnAngle = prepared.turnAngle
        )
        context.applicationContext.sendBroadcast(intent)
    }

    fun showNotificationForSnapshot(snapshot: NavigationSnapshot, distanceMeters: Int?) {
        val detail = snapshot.nextTurnDetail?.payload
        val action = detail
            ?.takeIf { it.hasNextTurn() }
            ?.let { nextEventToAction(it.nextTurn) }
            ?: context.getString(R.string.nav_action_unknown)
        val street = (snapshot.currentStreet?.payload ?: detail?.road ?: "").ifBlank { "—" }
        showNotification(distanceMeters = distanceMeters, action = action, street = street)
    }

    fun cancelNotification() {
        val appContext = context.applicationContext
        (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NAV_NOTIFICATION_ID)
    }

    private fun prepareFullNavigationMessage(snapshot: NavigationSnapshot, navEventType: Int): FullNavigationMessage {
        val detail = snapshot.nextTurnDetail?.payload
        val turnDistance = snapshot.nextTurnDistance?.payload
        val state = snapshot.navigationState?.payload
        val currentPosition = snapshot.currentPosition?.payload

        val stepDistance = currentPosition?.takeIf { it.hasStepDistance() }?.stepDistance
        val distanceMeters = stepDistance
            ?.takeIf { it.hasDistance() && it.distance.hasMeters() }
            ?.distance
            ?.meters
            ?: turnDistance?.distanceMeters?.takeIf { it >= 0 }
        val timeSeconds = stepDistance
            ?.takeIf { it.hasTimeToStepSeconds() }
            ?.timeToStepSeconds
            ?.coerceAtLeast(0L)
            ?.coerceAtMost(Int.MAX_VALUE.toLong())
            ?.toInt()
            ?: turnDistance?.timeToTurnSeconds?.takeIf { it >= 0 }

        val roadFromPosition = currentPosition
            ?.takeIf { it.hasCurrentRoad() && it.currentRoad.hasName() }
            ?.currentRoad
            ?.name
            ?.takeIf { it.isNotBlank() }
        val roadFromState = state?.stepsList?.firstOrNull()
            ?.takeIf { it.hasRoad() && it.road.hasName() }
            ?.road
            ?.name
            ?.takeIf { it.isNotBlank() }
        val road = (roadFromPosition
            ?: snapshot.currentStreet?.payload?.takeIf { it.isNotBlank() }
            ?: roadFromState
            ?: detail?.road?.takeIf { it.isNotBlank() }
            ?: "").ifBlank { "—" }

        val nextEventType = state?.stepsList?.firstOrNull()?.maneuver?.type?.number
            ?: detail?.takeIf { it.hasNextTurn() }?.nextTurn?.number
            ?: NavigationStatus.NextTurnDetail.NextEvent.UNKNOWN.number
        val turnSide = detail
            ?.takeIf { it.hasSide() }
            ?.side
            ?.number
        val turnNumber = state?.stepsList?.firstOrNull()?.maneuver?.roundaboutExitNumber
            ?: detail?.takeIf { it.hasTurnNumber() }?.turnNumber
        val turnAngle = state?.stepsList?.firstOrNull()?.maneuver?.roundaboutExitAngle
            ?: detail?.takeIf { it.hasTurnAngle() }?.turnAngle
        val actionFromDetail =  state?.stepsList?.firstOrNull()?.maneuver?.type?.let { maneuverTypeToAction(it) }
            ?: detail?.takeIf { it.hasNextTurn() }?.let { nextEventToAction(it.nextTurn) }
        val actionFromState = state?.stepsList?.firstOrNull()
            ?.takeIf { it.hasManeuver() }
            ?.maneuver
            ?.type
            ?.let { maneuverTypeToAction(it) }
        val actionText = actionFromDetail ?: actionFromState ?: context.getString(R.string.nav_action_unknown)

        AppLog.d(
            "Nav: emit debounced eventType=$navEventType " +
                "statusAt=${snapshot.clusterStatus?.updatedAtElapsedRealtimeMs} " +
                "turnAt=${snapshot.nextTurnDetail?.updatedAtElapsedRealtimeMs} " +
                "distanceAt=${snapshot.nextTurnDistance?.updatedAtElapsedRealtimeMs} " +
                "stateAt=${snapshot.navigationState?.updatedAtElapsedRealtimeMs} " +
                "positionAt=${snapshot.currentPosition?.updatedAtElapsedRealtimeMs}"
        )

        return FullNavigationMessage(
            distanceMeters = distanceMeters,
            timeSeconds = timeSeconds,
            road = road,
            nextEventType = nextEventType,
            actionText = actionText,
            turnSide = turnSide,
            turnNumber = turnNumber,
            turnAngle = turnAngle
        )
    }

    private fun showNotification(distanceMeters: Int?, action: String, street: String) {
        val appContext = context.applicationContext
        val title = if (distanceMeters != null && distanceMeters >= 0) {
            context.getString(R.string.nav_notification_title_format, distanceMeters, action)
        } else {
            action
        }
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val notification = NotificationCompat.Builder(appContext, NAV_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.nav_notification_street_format, street))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    appContext,
                    0,
                    AapProjectionActivity.intent(appContext),
                    pendingIntentFlags
                )
            )
            .build()
        (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NAV_NOTIFICATION_ID, notification)
    }

    private fun nextEventToAction(nextEvent: NavigationStatus.NextTurnDetail.NextEvent): String {
        return when (nextEvent) {
            NavigationStatus.NextTurnDetail.NextEvent.UNKNOWN -> context.getString(R.string.nav_action_unknown)
            NavigationStatus.NextTurnDetail.NextEvent.DEPART -> context.getString(R.string.nav_action_depart)
            NavigationStatus.NextTurnDetail.NextEvent.NAME_CHANGE -> context.getString(R.string.nav_action_name_change)
            NavigationStatus.NextTurnDetail.NextEvent.SLIGHT_TURN -> context.getString(R.string.nav_action_slight_turn)
            NavigationStatus.NextTurnDetail.NextEvent.TURN -> context.getString(R.string.nav_action_turn)
            NavigationStatus.NextTurnDetail.NextEvent.SHARP_TURN -> context.getString(R.string.nav_action_sharp_turn)
            NavigationStatus.NextTurnDetail.NextEvent.U_TURN -> context.getString(R.string.nav_action_uturn)
            NavigationStatus.NextTurnDetail.NextEvent.ON_RAMP -> context.getString(R.string.nav_action_on_ramp)
            NavigationStatus.NextTurnDetail.NextEvent.OFFRAMP -> context.getString(R.string.nav_action_off_ramp)
            NavigationStatus.NextTurnDetail.NextEvent.FORK -> context.getString(R.string.nav_action_merge)
            NavigationStatus.NextTurnDetail.NextEvent.MERGE -> context.getString(R.string.nav_action_merge)
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER -> context.getString(R.string.nav_action_roundabout_enter)
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_EXIT -> context.getString(R.string.nav_action_roundabout_exit)
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER_AND_EXIT -> context.getString(R.string.nav_action_roundabout)
            NavigationStatus.NextTurnDetail.NextEvent.STRAIGHT -> context.getString(R.string.nav_action_straight)
            NavigationStatus.NextTurnDetail.NextEvent.FERRY_BOAT -> context.getString(R.string.nav_action_ferry)
            NavigationStatus.NextTurnDetail.NextEvent.FERRY_TRAIN -> context.getString(R.string.nav_action_ferry_train)
            NavigationStatus.NextTurnDetail.NextEvent.DESTINATION -> context.getString(R.string.nav_action_destination)
        }
    }

    private fun maneuverTypeToAction(type: NavigationStatus.NavigationManeuver.NavigationType): String {
        return when (type) {
            NavigationStatus.NavigationManeuver.NavigationType.DEPART -> context.getString(R.string.nav_action_depart)
            NavigationStatus.NavigationManeuver.NavigationType.NAME_CHANGE -> context.getString(R.string.nav_action_name_change)
            NavigationStatus.NavigationManeuver.NavigationType.KEEP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.KEEP_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.U_TURN_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.U_TURN_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SHARP_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_SHARP_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_U_TURN_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_U_TURN_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_SLIGHT_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_SLIGHT_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.FORK_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.FORK_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_RIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.MERGE_SIDE_UNSPECIFIED -> context.getString(R.string.nav_action_turn)
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_EXIT,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW,
            NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE -> context.getString(R.string.nav_action_roundabout)
            NavigationStatus.NavigationManeuver.NavigationType.STRAIGHT -> context.getString(R.string.nav_action_straight)
            NavigationStatus.NavigationManeuver.NavigationType.FERRY_BOAT -> context.getString(R.string.nav_action_ferry)
            NavigationStatus.NavigationManeuver.NavigationType.FERRY_TRAIN -> context.getString(R.string.nav_action_ferry_train)
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_STRAIGHT,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_LEFT,
            NavigationStatus.NavigationManeuver.NavigationType.DESTINATION_RIGHT -> context.getString(R.string.nav_action_destination)
            NavigationStatus.NavigationManeuver.NavigationType.UNKNOWN -> context.getString(R.string.nav_action_unknown)
        }
    }

    companion object {
        const val NAV_CHANNEL_ID = "headunit_navigation"
        private const val NAV_NOTIFICATION_ID = 2

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NAV_CHANNEL_ID,
                    context.getString(R.string.nav_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.nav_notification_channel_description)
                    setShowBadge(false)
                }
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(channel)
            }
        }
    }
}
