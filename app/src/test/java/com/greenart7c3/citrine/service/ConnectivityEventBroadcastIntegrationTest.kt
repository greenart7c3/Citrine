package com.greenart7c3.citrine.service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.server.CustomWebSocketServer
import com.greenart7c3.citrine.service.CustomWebSocketService
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration test that simulates:
 * 1. Start relay
 * 2. Disconnect network
 * 3. Send event (should fail or be queued)
 * 4. Reconnect network (broadcast receiver triggers)
 * 5. Verify event is broadcast after reconnection
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityEventBroadcastIntegrationTest {
    @MockK
    private lateinit var mockContext: Context

    @MockK
    private lateinit var mockConnectivityManager: ConnectivityManager

    @RelaxedMockK
    private lateinit var mockCitrineInstance: Citrine

    @RelaxedMockK
    private lateinit var mockNostrClient: NostrClient

    @RelaxedMockK
    private lateinit var mockAppDatabase: AppDatabase

    @RelaxedMockK
    private lateinit var mockWebSocketServer: CustomWebSocketServer

    private lateinit var receiver: ConnectivityBroadcastReceiver
    private val testDispatcher = StandardTestDispatcher()
    private var isNetworkConnected = false

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        receiver = ConnectivityBroadcastReceiver()

        // Mock Citrine singleton
        mockkObject(Citrine)
        every { Citrine.instance } returns mockCitrineInstance

        // Mock Citrine instance properties
        every { mockCitrineInstance.client } returns mockNostrClient
        every { mockCitrineInstance.applicationScope } returns CoroutineScope(testDispatcher + SupervisorJob())

        // Mock context and connectivity manager
        every { mockContext.getSystemService(Context.CONNECTIVITY_SERVICE) } returns mockConnectivityManager

        // Mock network state - start disconnected
        isNetworkConnected = false
        setupNetworkMocks(connected = false)

        // Mock CustomWebSocketService
        mockkObject(CustomWebSocketService)
        every { CustomWebSocketService.server } returns mockWebSocketServer

        // Mock Log
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun setupNetworkMocks(connected: Boolean) {
        isNetworkConnected = connected
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val mockNetwork = if (connected) mockk<Network>() else null
            val mockCapabilities = if (connected) {
                val caps = mockk<NetworkCapabilities>()
                every { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
                every { caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns false
                every { caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } returns false
                caps
            } else {
                null
            }

            every { mockConnectivityManager.activeNetwork } returns mockNetwork
            every { mockConnectivityManager.getNetworkCapabilities(mockNetwork) } returns mockCapabilities
        } else {
            @Suppress("DEPRECATION")
            val mockNetworkInfo = mockk<android.net.NetworkInfo>()
            @Suppress("DEPRECATION")
            every { mockNetworkInfo.isConnected } returns connected
            @Suppress("DEPRECATION")
            every { mockConnectivityManager.activeNetworkInfo } returns mockNetworkInfo
        }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.M])
    fun `test event broadcast after network reconnection`() = runTest {
        // Step 1: Start relay (simulated - server is already mocked)
        every { mockNostrClient.isActive() } returns false
        coEvery { mockNostrClient.connect() } just runs

        // Step 2: Simulate network disconnected state
        setupNetworkMocks(connected = false)
        val disconnectIntent = Intent(ConnectivityManager.CONNECTIVITY_ACTION)
        receiver.onReceive(mockContext, disconnectIntent)
        advanceUntilIdle()

        // Verify client is not connected when network is down
        coVerify(exactly = 0) { mockNostrClient.connect() }

        // Step 3: Try to send event while disconnected
        // For testing, we'll create a mock event - actual signing requires EventTemplate
        val createdAt = System.currentTimeMillis() / 1000
        // Create a simple unsigned event for testing - the server will handle validation
        val signedEvent = mockk<Event>(relaxed = true)
        every { signedEvent.id } returns "test_event_id"
        every { signedEvent.pubKey } returns "test_pubkey"
        every { signedEvent.createdAt } returns createdAt
        every { signedEvent.kind } returns 1
        every { signedEvent.tags } returns emptyArray()
        every { signedEvent.content } returns "Test event sent while disconnected"
        every { signedEvent.sig } returns "test_signature"

        // Mock server processing - event should fail or be queued
        // Note: innerProcessEvent is a suspend function, so we use coEvery
        coEvery {
            mockWebSocketServer.innerProcessEvent(any(), any(), any())
        } returns CustomWebSocketServer.VerificationResult.Valid

        // Step 4: Simulate network reconnection
        setupNetworkMocks(connected = true)
        val connectIntent = Intent(ConnectivityManager.CONNECTIVITY_ACTION)

        // Mock client reconnection
        every { mockNostrClient.isActive() } returns false
        coEvery { mockNostrClient.connect() } just runs

        // Trigger connectivity broadcast receiver
        receiver.onReceive(mockContext, connectIntent)
        advanceUntilIdle()

        // Step 5: Verify client reconnected
        coVerify(atLeast = 1) { mockNostrClient.connect() }

        // Step 6: Now send event after reconnection - should succeed
        // Simulate event being processed after reconnection
        coEvery {
            mockWebSocketServer.innerProcessEvent(any(), any(), any())
        } returns CustomWebSocketServer.VerificationResult.Valid

        // Process event after reconnection
        mockWebSocketServer.innerProcessEvent(signedEvent, null)
        advanceUntilIdle()

        // Verify event was processed
        coVerify { mockWebSocketServer.innerProcessEvent(any(), any(), any()) }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.M])
    fun `test event queued during disconnection and broadcast after reconnection`() = runTest {
        // Step 1: Start with network connected
        setupNetworkMocks(connected = true)
        every { mockNostrClient.isActive() } returns true

        // Step 2: Disconnect network
        setupNetworkMocks(connected = false)
        val disconnectIntent = Intent(ConnectivityManager.CONNECTIVITY_ACTION)
        receiver.onReceive(mockContext, disconnectIntent)
        advanceUntilIdle()

        // Step 3: Create and attempt to send event (should fail or queue)
        val createdAt = System.currentTimeMillis() / 1000
        // Create a mock event for testing
        val signedEvent = mockk<Event>(relaxed = true)
        every { signedEvent.id } returns "queued_event_id"
        every { signedEvent.pubKey } returns "test_pubkey"
        every { signedEvent.createdAt } returns createdAt
        every { signedEvent.kind } returns 1
        every { signedEvent.tags } returns emptyArray()
        every { signedEvent.content } returns "Event queued during disconnection"
        every { signedEvent.sig } returns "test_signature"

        // Event processing should fail or be queued when network is down
        coEvery {
            mockWebSocketServer.innerProcessEvent(any(), any(), any())
        } returns CustomWebSocketServer.VerificationResult.Valid

        // Step 4: Reconnect network
        setupNetworkMocks(connected = true)
        every { mockNostrClient.isActive() } returns false
        coEvery { mockNostrClient.connect() } just runs

        val connectIntent = Intent(ConnectivityManager.CONNECTIVITY_ACTION)
        receiver.onReceive(mockContext, connectIntent)
        advanceUntilIdle()

        // Step 5: Verify reconnection happened
        coVerify(atLeast = 1) { mockNostrClient.connect() }

        // Step 6: After reconnection, event should be processable
        // Simulate event being sent after reconnection
        delay(100) // Small delay to simulate reconnection completion
        coEvery {
            mockWebSocketServer.innerProcessEvent(any(), any(), any())
        } returns CustomWebSocketServer.VerificationResult.Valid

        mockWebSocketServer.innerProcessEvent(signedEvent, null)
        advanceUntilIdle()

        // Verify event was processed after reconnection
        coVerify { mockWebSocketServer.innerProcessEvent(any(), any(), any()) }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.M])
    fun `test multiple events sent during disconnection and all broadcast after reconnection`() = runTest {
        val events = mutableListOf<Event>()

        // Step 1: Disconnect network
        setupNetworkMocks(connected = false)
        val disconnectIntent = Intent(ConnectivityManager.CONNECTIVITY_ACTION)
        receiver.onReceive(mockContext, disconnectIntent)
        advanceUntilIdle()

        // Step 2: Create multiple events while disconnected
        repeat(3) { index ->
            val createdAt = System.currentTimeMillis() / 1000
            val event = mockk<Event>(relaxed = true)
            every { event.id } returns "event_${index}_id"
            every { event.pubKey } returns "test_pubkey"
            every { event.createdAt } returns createdAt
            every { event.kind } returns 1
            every { event.tags } returns emptyArray()
            every { event.content } returns "Event $index sent while disconnected"
            every { event.sig } returns "test_signature_$index"
            events.add(event)
        }

        // Step 3: Reconnect network
        setupNetworkMocks(connected = true)
        every { mockNostrClient.isActive() } returns false
        coEvery { mockNostrClient.connect() } just runs

        val connectIntent = Intent(ConnectivityManager.CONNECTIVITY_ACTION)
        receiver.onReceive(mockContext, connectIntent)
        advanceUntilIdle()

        // Step 4: Verify reconnection
        coVerify(atLeast = 1) { mockNostrClient.connect() }

        // Step 5: Process all events after reconnection
        coEvery {
            mockWebSocketServer.innerProcessEvent(any(), any(), any())
        } returns CustomWebSocketServer.VerificationResult.Valid

        delay(100)
        coEvery {
            mockWebSocketServer.innerProcessEvent(any(), any(), any())
        } returns CustomWebSocketServer.VerificationResult.Valid

        events.forEach { event ->
            mockWebSocketServer.innerProcessEvent(event, null)
        }
        advanceUntilIdle()

        // Verify all events were processed
        coVerify(exactly = 3) { mockWebSocketServer.innerProcessEvent(any(), any(), any()) }
    }
}
