// NebuFlow-IoT : ESP32 firmware (breath-actuated smart nebulizer)
// Control loop (core 1): reads BMP180, opens the relay/solenoid when the pressure drop
// exceeds the selected threshold. Network task (core 0): posts data to the Java server
// and receives mode / enable commands from the website. WiFi can never delay the valve.

#include <Arduino.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <Wire.h>
#include <Adafruit_BMP085.h>

// ======== EDIT THESE THREE LINES ========
const char* WIFI_SSID  = "YOUR_WIFI_NAME";
const char* WIFI_PASS  = "YOUR_WIFI_PASSWORD";
const char* SERVER_URL = "http://192.168.1.50:8080/api/telemetry";   // PC's IP printed by the Java server
// ========================================

const char* DEVICE_ID = "NEBUFLOW-ESP32-01";

// Pins (from the project circuit)
const int PIN_SDA = 21, PIN_SCL = 22;
const int BTN_LIGHT = 12, BTN_MODERATE = 14, BTN_HEAVY = 27;   // buttons to GND, internal pull-ups
const int PIN_RELAY = 26;                                      // HIGH = solenoid valve open

// Sensitivity modes: pressure drop (Pa) needed to open the valve
const char* MODE_NAMES[3] = {"light", "moderate", "heavy"};
const int   MODE_THRESHOLD[3] = {200, 500, 1000};

Adafruit_BMP085 bmp;   // works for BMP180 too

// Shared between the two tasks
volatile int   g_mode = 1;            // 0 light, 1 moderate, 2 heavy
volatile bool  g_enabled = true;      // set from the website
volatile bool  g_valve = false;
volatile float g_drop = 0;            // baseline - current pressure (Pa)
volatile float g_temp = 0;            // deg C
volatile long  g_lastCmdSeq = -1;

float baseline = 101325;              // Pa, calibrated at start-up
unsigned long lastBtn = 0, lastTemp = 0;

float readPressure() { return (float)bmp.readPressure(); }

void calibrateBaseline() {
  double sum = 0;
  for (int i = 0; i < 30; i++) { sum += readPressure(); delay(40); }
  baseline = sum / 30.0;
  Serial.printf("Baseline pressure: %.0f Pa\n", baseline);
}

void handleButtons() {
  if (millis() - lastBtn < 250) return;
  int m = -1;
  if (digitalRead(BTN_LIGHT) == LOW) m = 0;
  else if (digitalRead(BTN_MODERATE) == LOW) m = 1;
  else if (digitalRead(BTN_HEAVY) == LOW) m = 2;
  if (m >= 0) {
    g_mode = m;
    lastBtn = millis();
    Serial.printf("Mode (button): %s, threshold %d Pa\n", MODE_NAMES[m], MODE_THRESHOLD[m]);
  }
}

// ---------- network task ----------
void applyServerReply(const String& r) {
  int i = r.indexOf("\"cmdSeq\":");
  if (i < 0) return;
  long seq = r.substring(i + 9).toInt();
  if (seq == g_lastCmdSeq) return;           // nothing new from the website
  g_lastCmdSeq = seq;

  int mi = r.indexOf("\"mode\":\"");
  if (mi >= 0) {
    int s = mi + 8, e = r.indexOf('"', s);
    String m = r.substring(s, e);
    for (int k = 0; k < 3; k++) if (m == MODE_NAMES[k]) g_mode = k;
  }
  g_enabled = r.indexOf("\"enabled\":true") >= 0;
  Serial.printf("Command from website: mode=%s enabled=%d\n", MODE_NAMES[g_mode], (int)g_enabled);
}

void netTask(void*) {
  for (;;) {
    if (WiFi.status() != WL_CONNECTED) {
      WiFi.reconnect();
      vTaskDelay(pdMS_TO_TICKS(2000));
      continue;
    }
    char body[320];
    int mode = g_mode;
    snprintf(body, sizeof(body),
      "{\"device\":\"%s\",\"pressure\":%.1f,\"temp\":%.1f,\"valve\":%d,\"mode\":\"%s\","
      "\"threshold\":%d,\"rssi\":%d,\"uptime\":%lu,\"baseline\":%.0f}",
      DEVICE_ID, (float)(g_drop < 0 ? 0 : g_drop), (float)g_temp, g_valve ? 1 : 0,
      MODE_NAMES[mode], MODE_THRESHOLD[mode], WiFi.RSSI(), millis() / 1000, (float)baseline);

    HTTPClient http;
    http.setConnectTimeout(1000);
    http.setTimeout(1000);
    http.begin(SERVER_URL);
    http.addHeader("Content-Type", "application/json");
    int code = http.POST((uint8_t*)body, strlen(body));
    if (code == 200) applyServerReply(http.getString());
    else Serial.printf("Server error / unreachable (code %d)\n", code);
    http.end();
    vTaskDelay(pdMS_TO_TICKS(500));          // 2 updates per second
  }
}

void setup() {
  Serial.begin(115200);
  pinMode(BTN_LIGHT, INPUT_PULLUP);
  pinMode(BTN_MODERATE, INPUT_PULLUP);
  pinMode(BTN_HEAVY, INPUT_PULLUP);
  pinMode(PIN_RELAY, OUTPUT);
  digitalWrite(PIN_RELAY, LOW);              // valve closed at boot

  Wire.begin(PIN_SDA, PIN_SCL);
  if (!bmp.begin(BMP085_ULTRAHIGHRES)) {
    Serial.println("BMP180 not found - check SDA=21 SCL=22 wiring");
    for (;;) delay(1000);
  }
  calibrateBaseline();
  g_temp = bmp.readTemperature();

  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  Serial.print("Connecting WiFi");
  for (int i = 0; i < 30 && WiFi.status() != WL_CONNECTED; i++) { delay(500); Serial.print("."); }
  Serial.println(WiFi.status() == WL_CONNECTED ? "\nWiFi connected, IP: " + WiFi.localIP().toString()
                                               : "\nWiFi not connected (device still works offline)");

  xTaskCreatePinnedToCore(netTask, "net", 8192, nullptr, 1, nullptr, 0);
}

void loop() {
  handleButtons();

  float p = readPressure();
  float drop = baseline - p;                 // inhalation lowers the pressure in the tube
  g_drop = drop;

  if (millis() - lastTemp > 1000) { g_temp = bmp.readTemperature(); lastTemp = millis(); }

  int thr = MODE_THRESHOLD[g_mode];
  bool open = g_valve;
  if (!g_enabled) open = false;
  else if (drop > thr) open = true;          // sharp inhale -> release medicine
  else if (drop < thr * 0.5f) open = false;  // hysteresis: close when breath fades

  // slowly follow weather / altitude drift while idle
  if (!open && fabsf(drop) < 100) baseline += (p - baseline) * 0.005f;

  if (open != g_valve) {
    g_valve = open;
    digitalWrite(PIN_RELAY, open ? HIGH : LOW);
    Serial.printf("%s | drop=%.0f Pa (threshold %d) -> valve %s\n",
                  MODE_NAMES[g_mode], drop, thr, open ? "OPEN" : "CLOSED");
  }
  delay(40);
}
