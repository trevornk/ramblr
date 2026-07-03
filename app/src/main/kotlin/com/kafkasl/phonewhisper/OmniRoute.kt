package com.kafkasl.phonewhisper

/** Fixed connection details for Trevor's self-hosted OmniRoute gateway (see ADR-0001). */
object OmniRoute {
    /** Reachable only on home LAN or VPN — NOT publicly reachable despite the hostname. */
    const val BASE_URL = "https://omniroute.example.com/v1"
}
