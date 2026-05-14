package com.andrerinas.headunitrevived.aap

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.aap.protocol.proto.NavigationStatus
import com.andrerinas.headunitrevived.contract.NavigationUpdateIntent
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings

/**
 * Handles navigation messages from the ID_NAV channel from any Android Auto-enabled app
 * (Google Maps, Yandex Maps, etc.). Shows notifications with turn-by-turn directions and current street.
 */
class AapNavigation(
    private val context: Context,
    private val settings: Settings
) {
    private val helper = AapNavigationHelper(context)
    private val snapshot = AapNavigationHelper.NavigationSnapshot()
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var isBroadcastScheduled = false
    private var pendingNavEventType = NAV_EVENT_TYPE_TURN

    private val debouncedBroadcastEmitter = Runnable {
        isBroadcastScheduled = false
        helper.sendFullNavigationBroadcast(snapshot, pendingNavEventType)
    }

    private var lastKnownDistance: Int? = null
    private var lastKnownTime: Int? = null

    fun process(message: AapMessage): Boolean {
        if (message.channel != Channel.ID_NAV) return false

        return when (message.type) {
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_START_VALUE -> {
                AppLog.d("Nav: Instrument cluster start")
                clearAccumulatedData()
                scheduleDebouncedBroadcast(NAV_EVENT_TYPE_START)
                true
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_STOP_VALUE -> {
                AppLog.d("Nav: Instrument cluster stop")
                clearAccumulatedData()
                scheduleDebouncedBroadcast(NAV_EVENT_TYPE_STOP)
                helper.cancelNotification()
                true
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE -> {
                try {
                    val status = message.parse(NavigationStatus.NavigationClusterStatus.newBuilder()).build()
                    AppLog.d("Nav: Navigation status=${status.status}")
                    updateClusterStatus(status)
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_STATUS)
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NavigationClusterStatus", e)
                    true
                }
            }
            NavigationStatus.MsgType.NEXTTURNDETAILS_VALUE -> {
                try {
                    val detail = message.parse(NavigationStatus.NextTurnDetail.newBuilder()).build()
                    lastTurnDetail = detail
                    currentStreet = detail.road.takeIf { it.isNotBlank() } ?: ""
                    AppLog.d("Nav: NextTurnDetail road=${detail.road} nextturn=${detail.nextturn}")
                    sendNavigationBroadcast(distanceMeters = lastKnownDistance, timeSeconds = lastKnownTime, detail = detail)
                    val detail = message.parse(NavigationStatus.NextTurnDetail.newBuilder()).buildPartial()
                    snapshot.nextTurnDetail = AapNavigationHelper.TimedMessage(detail, helper.nowElapsedRealtimeMs())
val road = detail.road.takeIf { it.isNotBlank() }
road?.let {
    snapshot.currentStreet = AapNavigationHelper.TimedMessage(it, helper.nowElapsedRealtimeMs())
}
                    AppLog.d(
                        "Nav: NextTurnDetail road=${detail.road} " +
                            "hasNextTurn=${detail.hasNextTurn()} nextTurn=${detail.nextTurn}"
                    )
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_TURN)
                    if (settings.showNavigationNotifications) {
                        val actionText = nextEventToAction(detail.nextturn)
                        val street = currentStreet.ifBlank { detail.road.takeIf { r -> r.isNotBlank() } ?: "" }.ifBlank { "—" }
                        showNotification(distanceMeters = lastKnownDistance, action = actionText, street = street)
                        helper.showNotificationForSnapshot(snapshot, distanceMeters = null)
                    }
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NextTurnDetail", e)
                    true
                }
            }
            NavigationStatus.MsgType.NEXTTURNDISTANCEANDTIME_VALUE -> {
                try {
                    val event = message.parse(NavigationStatus.NextTurnDistanceEvent.newBuilder()).build()
                    val distanceMeters = if (event.hasDistance()) event.distance else null
                    val timeSeconds = if (event.hasTime()) event.time else null

                    if (distanceMeters != null) lastKnownDistance = distanceMeters
                    if (timeSeconds != null) lastKnownTime = timeSeconds

                    AppLog.i("Nav: NextTurnDistanceEvent distance=$distanceMeters (uint32) time=$timeSeconds")

                    val detail = lastTurnDetail
                    val actionText = detail?.let { nextEventToAction(it.nextturn) } ?: context.getString(R.string.nav_action_unknown)
                    val street = currentStreet.ifBlank { detail?.road?.takeIf { r -> r.isNotBlank() } ?: "" }.ifBlank { "—" }
                    sendNavigationBroadcast(distanceMeters = lastKnownDistance, timeSeconds = lastKnownTime, detail = detail)
                    val event = message.parse(NavigationStatus.NextTurnDistanceEvent.newBuilder()).buildPartial()
                    snapshot.nextTurnDistance = AapNavigationHelper.TimedMessage(event, helper.nowElapsedRealtimeMs())
                    val distanceMeters = event.distanceMeters.takeIf { it >= 0 }
                    AppLog.d(
                        "Nav: NextTurnDistanceEvent hasDistance=${event.hasDistanceMeters()} " +
                            "distance=${event.distanceMeters} hasTime=${event.hasTimeToTurnSeconds()} " +
                            "time=${event.timeToTurnSeconds}"
                    )
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_TURN)
                    if (settings.showNavigationNotifications) {
                        helper.showNotificationForSnapshot(snapshot, distanceMeters = distanceMeters)
                        showNotification(
                            distanceMeters = lastKnownDistance,
                            action = actionText,
                            street = street
                        )
                    }
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NextTurnDistanceEvent", e)
                    true
                }
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE -> {
                try {
                    val state = message.parse(NavigationStatus.NavigationState.newBuilder()).build()
                    snapshot.navigationState = AapNavigationHelper.TimedMessage(state, helper.nowElapsedRealtimeMs())
                    val firstStepRoad = state.stepsList.firstOrNull()
                        ?.takeIf { it.hasRoad() && it.road.hasName() }
                        ?.road
                        ?.name
                        ?.takeIf { it.isNotBlank() }
                    if (!firstStepRoad.isNullOrBlank()) {
                        snapshot.currentStreet = AapNavigationHelper.TimedMessage(firstStepRoad, helper.nowElapsedRealtimeMs())
                    lastNavState = state

                    if (state.stepsList.isEmpty() && state.destinationsList.isEmpty()) {
                        AppLog.i("Nav: Navigation stopped, clearing cache")
                        lastKnownDistance = null
                        lastKnownTime = null
                    }

                    val firstStep = state.stepsList.firstOrNull()
                    if (firstStep != null) {
                        currentStreet = firstStep.road?.name ?: ""
                        AppLog.d("Nav: NavigationState road=${currentStreet} type=${firstStep.maneuver?.type}")

                        // Trigger initial broadcast for the new step
                        processNewProtocolUpdate()
                    }
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_STATE)
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NavigationState", e)
                    true
                }
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE -> {
                try {
                    val position = message.parse(NavigationStatus.NavigationCurrentPosition.newBuilder()).build()
                    snapshot.currentPosition = AapNavigationHelper.TimedMessage(position, helper.nowElapsedRealtimeMs())
                    val road = position
                        .takeIf { it.hasCurrentRoad() && it.currentRoad.hasName() }
                        ?.currentRoad
                        ?.name
                        ?.takeIf { it.isNotBlank() }
                        ?: snapshot.currentStreet?.payload
                    if (!road.isNullOrBlank()) {
                        snapshot.currentStreet = AapNavigationHelper.TimedMessage(road, helper.nowElapsedRealtimeMs())
                    }
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_CURRENT_POSITION)
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NavigationCurrentPosition", e)
                    true
                }
            }
            else -> {
                AppLog.d("Nav: passthrough type ${message.type}")
                false
            }
        }
    }

    private fun clearAccumulatedData() {
        snapshot.clusterStatus = null
        snapshot.nextTurnDetail = null
        snapshot.nextTurnDistance = null
        snapshot.navigationState = null
        snapshot.currentPosition = null
        snapshot.currentStreet = null
    private fun processNewProtocolUpdate() {
        val state = lastNavState ?: return
        val pos = lastCurrentPosition
        val firstStep = state.stepsList.firstOrNull() ?: return
        val maneuver = firstStep.maneuver

        // Try step distance first, then fall back to destination distance if available
        val rawDist = if (pos?.hasStepDistance() == true) {
            pos.stepDistance.distanceM
        } else {
            pos?.destinationDistancesList?.firstOrNull()?.takeIf { it.hasDistanceM() }?.distanceM
        }

        val distanceMeters = rawDist?.let { Math.round(it).toInt() }
        val timeSeconds = pos?.destinationDistancesList?.firstOrNull()?.let {
            if (it.hasTimeToArrivalS()) it.timeToArrivalS else null
        }

        if (distanceMeters != null) lastKnownDistance = distanceMeters
        if (timeSeconds != null) lastKnownTime = timeSeconds

        AppLog.i("Nav: ProtocolUpdate distanceMeters=$distanceMeters (raw=$rawDist) timeSeconds=$timeSeconds (hasStepDist=${pos?.hasStepDistance()})")

        val road = firstStep.road?.name ?: currentStreet
        val nextEvent = mapNavigationTypeToNextEvent(maneuver?.type ?: NavigationStatus.NavigationManeuver.NavigationType.UNKNOWN)
        val turnSide = mapNavigationTypeToSide(maneuver?.type ?: NavigationStatus.NavigationManeuver.NavigationType.UNKNOWN)

        val turnNumber = maneuver?.roundaboutExitNumber
        val turnAngle = maneuver?.roundaboutExitAngle

        sendNavigationBroadcast(
            distanceMeters = lastKnownDistance,
            timeSeconds = lastKnownTime,
            road = road,
            nextEventType = nextEvent,
            turnSide = turnSide,
            turnNumber = turnNumber,
            turnAngle = turnAngle
        )

        if (settings.showNavigationNotifications) {
            val actionText = nextEventToAction(nextEvent)
            val street = road.ifBlank { currentStreet }.ifBlank { "—" }
            showNotification(distanceMeters = lastKnownDistance, action = actionText, street = street)
        }
    }

    private fun clearAccumulatedDataPreservingStatus(
        status: AapNavigationHelper.TimedMessage<NavigationStatus.NavigationClusterStatus>
    ) {
        clearAccumulatedData()
        snapshot.clusterStatus = status
    }

    private fun updateClusterStatus(status: NavigationStatus.NavigationClusterStatus) {
        val now = helper.nowElapsedRealtimeMs()
        val newStatus = AapNavigationHelper.TimedMessage(status, now)
        val previous = snapshot.clusterStatus?.payload?.status
        val changed = previous != null && previous != status.status
        if (changed) {
            clearAccumulatedDataPreservingStatus(newStatus)
            helper.cancelNotification()
        } else {
            snapshot.clusterStatus = newStatus
        }
    }

    private fun scheduleDebouncedBroadcast(navEventType: Int) {
        pendingNavEventType = navEventType
        if (isBroadcastScheduled) return
        isBroadcastScheduled = true
        debounceHandler.postDelayed(debouncedBroadcastEmitter, BROADCAST_DEBOUNCE_MS)
    }

    companion object {
        private const val BROADCAST_DEBOUNCE_MS = 1000L
        private const val NAV_EVENT_TYPE_TURN = 0
        private const val NAV_EVENT_TYPE_START = 1
        private const val NAV_EVENT_TYPE_STOP = 2
        private const val NAV_EVENT_TYPE_STATUS = 3
        private const val NAV_EVENT_TYPE_STATE = 4
        private const val NAV_EVENT_TYPE_CURRENT_POSITION = 5

        fun createNotificationChannel(context: Context) {
            AapNavigationHelper.createNotificationChannel(context)
        }
    }
}
