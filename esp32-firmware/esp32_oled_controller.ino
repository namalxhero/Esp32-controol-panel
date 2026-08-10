/*
  ESP32 OLED Controller — example firmware
  -----------------------------------------
  Matches the protocol the Android app (Protocol.kt) expects over USB-serial
  at 115200 baud. No physical OLED needed — the phone app IS the display.

  Wire format (one line each, newline terminated):
    #OLED:<base64 of 1024 bytes>   -> full 128x64 1-bit framebuffer (SSD1306 layout:
                                       8 pages x 128 columns, LSB = top pixel of page)
    #CMDS:cmd1,cmd2,cmd3           -> tell the app which commands to show as chips
    anything else                  -> shown as a plain log line in the Terminal tab

  Incoming from the app (commands you send from the phone) arrive as plain
  newline-terminated text on Serial - just read them with Serial.readStringUntil('\n').

  Uses the Adafruit_GFX + Adafruit_SSD1306 libraries purely as an in-memory
  canvas (display.begin() is skipped — nothing is actually wired to a real
  screen). Install both libraries via Library Manager if you want to keep
  using familiar drawing calls; otherwise write directly into a 1024-byte
  buffer yourself and skip this dependency entirely.
*/

#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include "base64.h" // built into ESP32 core (base64.h / base64.cpp)

#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64
#define OLED_BYTES (SCREEN_WIDTH * SCREEN_HEIGHT / 8)

// -1 = no reset pin; we never call begin() against real hardware
Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, -1);

const char* SUPPORTED_COMMANDS[] = { "LED_ON", "LED_OFF", "STATUS", "RESTART" };
const int NUM_COMMANDS = 4;

bool ledState = false;
unsigned long lastFrame = 0;

void setup() {
  Serial.begin(115200);
  delay(300);

  // Prepare an in-memory framebuffer only (no display.begin() -> no real I2C init needed)
  display.clearDisplay();

  sendCommandList();
  sendLog("ESP32 ready.");
}

void loop() {
  // 1. Handle incoming commands from the phone app
  if (Serial.available()) {
    String cmd = Serial.readStringUntil('\n');
    cmd.trim();
    if (cmd.length() > 0) handleCommand(cmd);
  }

  // 2. Push a fresh OLED frame ~4x/second so the app mirrors what would be on screen
  if (millis() - lastFrame > 250) {
    lastFrame = millis();
    drawDemoFrame();
    sendOledFrame();
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
  Serial.print("#OLED:");
  Serial.println(encoded);
}

void sendCommandList() {
  Serial.print("#CMDS:");
  for (int i = 0; i < NUM_COMMANDS; i++) {
    Serial.print(SUPPORTED_COMMANDS[i]);
    if (i < NUM_COMMANDS - 1) Serial.print(",");
  }
  Serial.println();
}

void sendLog(String msg) {
  Serial.println(msg);
}
