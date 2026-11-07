package com.trevornk.ramblr

import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkClientsGithubTest {
    @Test fun `updater clients inherit Happy Eyeballs`() {
        assertTrue(SelfUpdateChecker.client.fastFallback)
        assertTrue(SelfUpdateInstallWorker.client.fastFallback)
    }
}
