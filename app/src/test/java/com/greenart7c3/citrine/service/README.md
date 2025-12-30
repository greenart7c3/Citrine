# Connectivity Broadcast Receiver Tests

This directory contains comprehensive test cases for the connectivity broadcast functionality.

## Test Files

### 1. `ConnectivityBroadcastReceiverTest.kt`
Unit tests for the `ConnectivityBroadcastReceiver` class covering:

- **Intent Filtering**: Tests that non-connectivity intents are ignored
- **Null Safety**: Tests handling of null connectivity manager
- **Android M+ (API 23+)**: Tests using NetworkCapabilities API
  - WiFi transport detection
  - Cellular transport detection
  - Ethernet transport detection
  - Client reconnection when disconnected
  - Skipping reconnection when already active
  - Handling disconnected state
- **Android Lollipop (API 21-22)**: Tests using deprecated NetworkInfo API
  - Connected state detection
  - Disconnected state detection
- **Error Handling**: Tests graceful handling of connection errors

**Test Cases:**
- `onReceive should ignore non-connectivity intents` - Verifies that only `CONNECTIVITY_ACTION` intents are processed
- `onReceive should return early if connectivity manager is null` - Tests null safety for connectivity service
- `onReceive should reconnect client when connected on Android M+` - Tests client reconnection on modern Android versions
- `onReceive should not reconnect if client is already active on Android M+` - Prevents unnecessary reconnection attempts
- `onReceive should handle cellular transport on Android M+` - Tests cellular network detection
- `onReceive should handle ethernet transport on Android M+` - Tests ethernet network detection
- `onReceive should not reconnect when disconnected on Android M+` - Verifies no reconnection when network is down
- `onReceive should reconnect client when connected on Android Lollipop` - Tests legacy Android API support
- `onReceive should not reconnect when disconnected on Android Lollipop` - Tests disconnected state on older Android
- `onReceive should handle connection errors gracefully` - Ensures exceptions during reconnection are caught and logged

### 2. `ConnectivityEventBroadcastIntegrationTest.kt`
Integration tests that simulate end-to-end scenarios for connectivity changes and event broadcasting:

- **Network Reconnection Flow**: Tests the complete flow from disconnection to reconnection
- **Event Broadcasting**: Verifies that events can be processed after network reconnection
- **Event Queuing**: Tests behavior when events are sent during network disconnection
- **Multiple Events**: Tests handling of multiple events during disconnection and after reconnection

**Test Cases:**
- `test event broadcast after network reconnection` - Simulates:
  1. Starting relay
  2. Network disconnection
  3. Attempting to send event while disconnected
  4. Network reconnection (broadcast receiver triggers)
  5. Verifying event can be broadcast after reconnection
  
- `test event queued during disconnection and broadcast after reconnection` - Tests:
  1. Starting with network connected
  2. Network disconnection
  3. Creating and attempting to send event (should fail or queue)
  4. Network reconnection
  5. Verifying event can be processed after reconnection
  
- `test multiple events sent during disconnection and all broadcast after reconnection` - Tests:
  1. Network disconnection
  2. Creating multiple events while disconnected
  3. Network reconnection
  4. Verifying all events are processed after reconnection

## Running Tests

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests ConnectivityBroadcastReceiverTest
./gradlew test --tests ConnectivityEventBroadcastIntegrationTest

# Run with coverage
./gradlew test jacocoTestReport
```

## Dependencies

- **JUnit 4**: Test framework
- **Robolectric**: Android framework mocking
- **MockK**: Kotlin-friendly mocking library
- **Kotlin Coroutines Test**: For testing coroutine-based code

## Notes

- Tests use `@Config` annotations to test different Android API levels
- MockK is used for mocking Android components and dependencies
- Coroutines are tested using `StandardTestDispatcher` for deterministic execution
- Tests verify both positive and negative scenarios

