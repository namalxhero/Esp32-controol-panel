package com.nipuna.esp32controller

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Triggers the compile-firmware.yml GitHub Action, polls until the build
 * finishes, downloads the .bin, then HTTP-POSTs it to the ESP32's /update
 * endpoint. Needs a GitHub Personal Access Token with 'repo' + 'actions'
 * scope, stored securely (EncryptedSharedPreferences, not hardcoded).
 */
class CloudBuildClient(
    private val githubToken: String,
    private val repoOwner: String,
    private val repoName: String,
    private val onStatus: (String) -> Unit
) {
    private val client = OkHttpClient()

    fun compileAndFlash(cppCode: String, esp32IpAddress: String) {
        onStatus("Sending code to GitHub…")
        val codeB64 = Base64.encodeToString(cppCode.toByteArray(), Base64.NO_WRAP)
        val body = JSONObject().apply {
            put("event_type", "compile-request")
            put("client_payload", JSONObject().put("code_b64", codeB64))
        }.toString().toRequestBody("application/json".toMediaType())

        val dispatchReq = Request.Builder()
            .url("https://api.github.com/repos/$repoOwner/$repoName/dispatches")
            .addHeader("Authorization", "Bearer $githubToken")
            .addHeader("Accept", "application/vnd.github+json")
            .post(body)
            .build()

        client.newCall(dispatchReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                onStatus("Failed to trigger build: ${resp.code}")
                return
            }
        }

        onStatus("Compiling on GitHub Actions…")
        pollForArtifact(esp32IpAddress)
    }

    private fun pollForArtifact(esp32IpAddress: String, attempt: Int = 0) {
        if (attempt > 40) { onStatus("Timed out waiting for build"); return }

        val runsReq = Request.Builder()
            .url("https://api.github.com/repos/$repoOwner/$repoName/actions/runs?per_page=1")
            .addHeader("Authorization", "Bearer $githubToken")
            .build()

        client.newCall(runsReq).execute().use { resp ->
            val json = JSONObject(resp.body?.string() ?: "{}")
            val run = json.getJSONArray("workflow_runs").optJSONObject(0)
            val status = run?.optString("status")
            val conclusion = run?.optString("conclusion")

            if (status == "completed" && conclusion == "success") {
                val artifactsUrl = run.getString("artifacts_url")
                downloadArtifact(artifactsUrl, esp32IpAddress)
            } else if (status == "completed") {
                onStatus("Build failed: $conclusion")
            } else {
                onStatus("Compiling… (${attempt * 5}s)")
                Thread.sleep(5000)
                pollForArtifact(esp32IpAddress, attempt + 1)
            }
        }
    }

    private fun downloadArtifact(artifactsUrl: String, esp32IpAddress: String) {
        val req = Request.Builder()
            .url(artifactsUrl)
            .addHeader("Authorization", "Bearer $githubToken")
            .build()
        client.newCall(req).execute().use { resp ->
            val json = JSONObject(resp.body?.string() ?: "{}")
            val artifact = json.getJSONArray("artifacts").optJSONObject(0)
                ?: run { onStatus("No artifact found"); return }
            val downloadUrl = artifact.getString("archive_download_url")

            val zipReq = Request.Builder()
                .url(downloadUrl)
                .addHeader("Authorization", "Bearer $githubToken")
                .build()
            client.newCall(zipReq).execute().use { zipResp ->
                val binFile = File.createTempFile("firmware", ".bin")
                ZipInputStream(zipResp.body?.byteStream()).use { zis ->
                    zis.nextEntry
                    binFile.outputStream().use { zis.copyTo(it) }
                }
                flashOverWifi(binFile, esp32IpAddress)
            }
        }
    }

    private fun flashOverWifi(binFile: File, esp32IpAddress: String) {
        onStatus("Flashing over WiFi…")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "firmware", "firmware.bin",
                binFile.asRequestBody("application/octet-stream".toMediaType())
            )
            .build()
        val req = Request.Builder()
            .url("http://$esp32IpAddress/update")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            onStatus(if (resp.isSuccessful) "Flashed! ESP32 restarting…" else "Flash failed: ${resp.code}")
        }
    }
}
