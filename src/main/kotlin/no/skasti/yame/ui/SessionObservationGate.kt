package no.skasti.yame.ui

import no.skasti.yame.ppp.session.PppObservation
import no.skasti.yame.ppp.session.PppSessionEnded
import no.skasti.yame.serial.ConnectionState
import no.skasti.yame.serial.ConnectionStateChanged

/** Consumer-owned high-water marks. Terminal states win over delayed open observations. */
internal class SessionObservationGate {
    var connectionId = 0L
        private set
    var sessionId = 0L
        private set
    private var connectionEnded = false
    private var sessionEnded = false
    private var connectionState = ConnectionState.CONNECTING

    fun accept(event: ConnectionStateChanged): Boolean {
        if (event.connectionId < connectionId) return false
        advanceConnection(event.connectionId)
        if (connectionEnded || event.state.ordinal < connectionState.ordinal) return false
        connectionState = event.state
        connectionEnded = event.state == ConnectionState.DISCONNECTED
        return true
    }

    fun accept(event: PppObservation): Boolean {
        if (event.session.connectionId < connectionId) return false
        advanceConnection(event.session.connectionId)
        if (connectionEnded || event.session.id < sessionId) return false
        if (event.session.id > sessionId) {
            sessionId = event.session.id
            sessionEnded = false
        }
        if (sessionEnded) return false
        if (event is PppSessionEnded) sessionEnded = true
        return true
    }

    private fun advanceConnection(id: Long) {
        if (id <= connectionId) return
        connectionId = id
        connectionEnded = false
        connectionState = ConnectionState.CONNECTING
        sessionId = 0
        sessionEnded = false
    }
}
