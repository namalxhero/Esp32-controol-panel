/*
  ESP32 OLED Controller — example firmware (USB-serial + BLE + WiFi OTA)
  ------------------------------------------------------------------------
  Matches the protocol the Android app (Protocol.kt) expects, sent over
  EITHER USB-serial at 115200 baud OR BLE (Nordic UART Service style),
  whichever the phone is connected through. No physical OLED needed —
  the phone app IS the display.

  Also runs a WiFi OTA HTTP endpoint (/update) so the app's CloudBuildClient
  can push freshly-compiled .bin files over the network after a GitHub
  Actions build finishes.

  Wire format (one line each, newline terminated):
    #OLED:<base64 of 1024 bytes>   -> full 128x64 1-bit framebuffer (SSD1306 layout:
                                       8 pages x 128 columns, LSB = top pixel of page)
    #CMDS:cmd1,cmd2,cmd3           -> tell the app which commands to show as chips
    anything else                  -> shown as a plain log line in the Terminal tab

  Incoming from the app (commands sent from the phone) arrive as plain
  newline-terminated text — either over Serial or over the BLE RX characteristic.

  BLE UUIDs below MUST match BleSerialManager.kt on the Android side.

  Requires: Adafruit_GFX, Adafruit_SSD1306, NimBLE-Arduino (h2zero) — all via
  Library Manager. WiFi/Update/WebServer are built into the ESP32 Arduino core.
*/

#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include "base64.h" // built into ESP32 core (base64.h / base64.cpp)
#include <NimBLEDevice.h>
#include <WiFi.h>
#include <Update.h>
#include <WebServer.h>

#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64
#define OLED_BYTES (SCREEN_WIDTH * SCREEN_HEIGHT / 8)

// -1 = no reset pin; we never call begin() against real hardware
Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, -1);

const char* SUPPORTED_COMMANDS[] = { "LED_ON", "LED_OFF", "STATUS", "RESTART" };
const int NUM_COMMANDS = 4;

bool ledState = false;
unsigned long lastFrame = 0;

// ---- BLE (Nordic UART Service style) — UUIDs must match BleSerialManager.kt ----
#define SERVICE_UUID           "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_RX "6E400002-B5A3-F393-E0A9-E50E24DCCA9E" // phone -> ESP32 (write)
#define CHARACTERISTIC_UUID_TX "6E400003-B5A3-F393-E0A9-E50E24DCCA9E" // ESP32 -> phone (notify)

NimBLEServer* pServer = nullptr;
NimBLECharacteristic* pTxCharacteristic = nullptr;
bool bleDeviceConnected = false;
uint16_t bleMtu = 23; // default ATT MTU before negotiation

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* srv) {
    bleDeviceConnected = true;
    sendLog("BLE client connected");
  }
  void onDisconnect(NimBLEServer* srv) {
    bleDeviceConnected = false;
    sendLog("BLE client disconnected");
    NimBLEDevice::startAdvertising();
  }
  void onMTUChange(uint16_t mtu, ble_gap_conn_desc* desc) {
    bleMtu = mtu;
  }
};

class RxCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* pChar) {
    std::string value = pChar->getValue();
    String cmd = String(value.c_str());
    cmd.trim();
    if (cmd.length() > 0) handleCommand(cmd);
  }
};

// ---- WiFi OTA ----
const char* WIFI_SSID = "YOUR_WIFI";
const char* WIFI_PASSWORD = "YOUR_PASSWORD";
WebServer otaServer(80);

void setup() {
  Serial.begin(115200);
  delay(300);

  // Prepare an in-memory framebuffer only (no display.begin() -> no real I2C init needed)
  display.clearDisplay();

  setupBLE();
  setupOta();

  sendCommandList();
  sendLog("ESP32 ready.");
}

void loop() {
  // 1. Handle incoming commands from the phone app over USB-serial
  if (Serial.available()) {
    String cmd = Serial.readStringUntil('\n');
    cmd.trim();
    if (cmd.length() > 0) handleCommand(cmd);
  }
  // (BLE incoming commands are handled asynchronously in RxCallbacks::onWrite)

  otaServer.handleClient();

  // 2. Push a fresh OLED frame ~4x/second so the app mirrors what would be on screen
  if (millis() - lastFrame > 250) {
    lastFrame = millis();
    drawDemoFrame();
    sendOledFrame();
  }
}

void setupBLE() {
  NimBLEDevice::init("ESP32-OLED-Controller");
  NimBLEDevice::setMTU(517); // request the largest MTU the app will negotiate down to

  pServer = NimBLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  NimBLEService* pService = pServer->createService(SERVICE_UUID);

  NimBLECharacteristic* pRxCharacteristic = pService->createCharacteristic(
      CHARACTERISTIC_UUID_RX,
      NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR
  );
  pRxCharacteristic->setCallbacks(new RxCallbacks());

  pTxCharacteristic = pService->createCharacteristic(
      CHARACTERISTIC_UUID_TX,
      NIMBLE_PROPERTY::NOTIFY
  );

  pService->start();

  NimBLEAdvertising* pAdvertising = NimBLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->start();
}

void setupOta() {
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  unsigned long wifiStart = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - wifiStart < 15000) {
    delay(300);
  }
  if (WiFi.status() != WL_CONNECTED) {
    sendLog("WiFi connect failed — OTA endpoint unavailable, BLE/USB still work");
    return;
  }
  sendLog("WiFi connected, IP: " + WiFi.localIP().toString());

  otaServer.on("/update", HTTP_POST, []() {
    otaServer.sendHeader("Connection", "close");
    otaServer.send(200, "text/plain", Update.hasError() ? "FAIL" : "OK");
    delay(500);
    ESP.restart();
  }, []() {
    HTTPUpload& upload = otaServer.upload();
    if (upload.status == UPLOAD_FILE_START) {
      Update.begin(UPDATE_SIZE_UNKNOWN);
    } else if (upload.status == UPLOAD_FILE_WRITE) {
      Update.write(upload.buf, upload.currentSize);
    } else if (upload.status == UPLOAD_FILE_END) {
      Update.end(true);
    }
  });
  otaServer.begin();
}

/** Sends one logical line over BLE notify, chunked to fit the negotiated MTU. */
void bleSendLine(const String &line) {
  if (!bleDeviceConnected || pTxCharacteristic == nullptr) return;
  String withNewline = line + "\n";
  size_t chunkSize = (bleMtu > 3) ? (bleMtu - 3) : 20;
  size_t len = withNewline.length();
  for (size_t i = 0; i < len; i += chunkSize) {
    size_t end = min(i + chunkSize, len);
    String chunk = withNewline.substring(i, end);
    pTxCharacteristic->setValue((uint8_t*)chunk.c_str(), chunk.length());
    pTxCharacteristic->notify();
    delay(3); // let the BLE stack drain its notify queue between chunks
  }
}

void handleCommand(String cmd) {
  if (cmd == "LED_ON") {
    ledState = true;
    digitalWrite(LED_BUILTIN, HIGH);
    sendLog("LED turned ON");
  } else if (cmd == "LED_OFF") {
    ledState = false;
    digitalWrite(LED_BUILTIN, LOW);
    sendLog("LED turned OFF");
  } else if (cmd == "STATUS") {
    sendLog(String("Uptime: ") + (millis() / 1000) + "s, LED=" + (ledState ? "ON" : "OFF"));
  } else if (cmd == "RESTART") {
    sendLog("Restarting…");
    delay(200);
    ESP.restart();
  } else {
    sendLog("Unknown command: " + cmd);
  }
}

void drawDemoFrame() {
  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(0, 0);
  display.println("ESP32 OLED Controller");
  display.print("Uptime: ");
  display.print(millis() / 1000);
  display.println("s");
  display.print("LED: ");
  display.println(ledState ? "ON" : "OFF");
  display.drawRect(0, 30, 128, 20, SSD1306_WHITE);
  int fillWidth = (millis() / 100) % 126;
  display.fillRect(1, 31, fillWidth, 18, SSD1306_WHITE);
}

void sendOledFrame() {
  uint8_t* buf = display.getBuffer(); // 1024 bytes, exact SSD1306 layout
  String encoded = base64::encode(buf, OLED_BYTES);
  String line = "#OLED:" + encoded;
  Serial.println(line);
  bleSendLine(line);
}

void sendCommandList() {
  String line = "#CMDS:";
  for (int i = 0; i < NUM_COMMANDS; i++) {
    line += SUPPORTED_COMMANDS[i];
    if (i < NUM_COMMANDS - 1) line += ",";
  }
  Serial.println(line);
  bleSendLine(line);
}

void sendLog(String msg) {
  Serial.println(msg);
  bleSendLine(msg);
}
