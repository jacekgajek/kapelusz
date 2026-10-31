package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.jacekgajek.kapelusz.app.data.runSuspendCatching
import com.jacekgajek.kapelusz.model.AttachmentKind
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.await
import kotlinx.coroutines.launch

/** What the JS picker resolves to: either [error] or a prepared file. */
private external interface PickedJs : JsAny {
    val error: String?
    val kind: String
    val name: String
    val size: Int
    val base64: String
}

// Opens the browser's file chooser (phones offer the camera there too) and prepares the chosen file: a PDF as it is,
// an image re-encoded through a canvas (orientation applied, longest edge 1600 px, JPEG 0.8), which drops EXIF/GPS.
// Several files can be chosen; resolves to one result each, in order, or null when the user cancels. The file content
// stays base64 on the JS side of the boundary.
private fun pickAndPrepare(): Promise<JsArray<PickedJs>?> = js(
    """new Promise((resolve) => {
  const input = document.createElement('input');
  input.type = 'file';
  input.accept = 'image/*,application/pdf';
  input.multiple = true;
  input.style.display = 'none';
  document.body.appendChild(input);
  const done = (value) => { input.remove(); resolve(value); };
  const toBase64 = (blob) => new Promise((ok, fail) => {
    const reader = new FileReader();
    reader.onload = () => ok(String(reader.result).split(',')[1] || '');
    reader.onerror = () => fail(reader.error);
    reader.readAsDataURL(blob);
  });
  input.addEventListener('cancel', () => done(null));
  const prepare = async (file) => {
    try {
      if (file.type === 'application/pdf' || /\.pdf$/i.test(file.name)) {
        if (file.size > 10485760) return { error: 'too_large' };
        return { kind: 'PDF', name: file.name, size: file.size, base64: await toBase64(file) };
      }
      if (!file.type.startsWith('image/')) return { error: 'unsupported' };
      let bitmap;
      try { bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' }); } catch (e) { return { error: 'unsupported' }; }
      const scale = Math.min(1, 1600 / Math.max(bitmap.width, bitmap.height));
      const canvas = document.createElement('canvas');
      canvas.width = Math.max(1, Math.round(bitmap.width * scale));
      canvas.height = Math.max(1, Math.round(bitmap.height * scale));
      canvas.getContext('2d').drawImage(bitmap, 0, 0, canvas.width, canvas.height);
      bitmap.close();
      const blob = await new Promise((ok) => canvas.toBlob(ok, 'image/jpeg', 0.8));
      if (!blob) return { error: 'failed' };
      return { kind: 'IMAGE', name: file.name.replace(/\.[^.]*$/, '') + '.jpg', size: blob.size, base64: await toBase64(blob) };
    } catch (e) {
      return { error: 'failed' };
    }
  };
  input.addEventListener('change', async () => {
    const files = input.files ? Array.from(input.files) : [];
    if (files.length === 0) { done(null); return; }
    // One after another: a canvas per photo at once would need that much more memory.
    const results = [];
    for (const file of files) results.push(await prepare(file));
    done(results);
  });
  input.click();
})""",
)

private fun toResult(picked: PickedJs): PickResult = when (picked.error) {
    null -> {
        val kind = AttachmentKind.valueOf(picked.kind)
        if (picked.size > kind.maxBytes) {
            PickResult.TooLarge
        } else {
            PickResult.Picked(PreparedFile(kind, picked.name, picked.size.toLong(), picked.base64))
        }
    }

    "too_large" -> PickResult.TooLarge

    "unsupported" -> PickResult.Unsupported

    else -> PickResult.Failed
}

@Composable
actual fun rememberAttachmentPicker(onResult: (List<PickResult>) -> Unit): AttachmentPicker {
    val scope = rememberCoroutineScope()
    val callback = rememberUpdatedState(onResult)
    return remember {
        object : AttachmentPicker {
            override fun pickFile() {
                // Undispatched: the chooser must open while the click is still being handled.
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val picked = runSuspendCatching { pickAndPrepare().await<JsArray<PickedJs>?>() }
                        .getOrElse { null.also { callback.value(listOf(PickResult.Failed)) } }
                        ?: return@launch
                    callback.value((0 until picked.length).mapNotNull { picked[it] }.map(::toResult))
                }
            }

            override val takePhoto: (() -> Unit)? = null
        }
    }
}

private fun putBase64(url: String, headersJson: String, base64: String): Promise<JsNumber> = js(
    """(() => {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return fetch(url, { method: 'PUT', headers: JSON.parse(headersJson), body: bytes }).then((r) => r.status, () => -1);
})()""",
)

internal suspend fun putFile(url: String, headers: Map<String, String>, base64: String): Int {
    val json = headers.entries.joinToString(",", "{", "}") { (key, value) -> "${jsonString(key)}:${jsonString(value)}" }
    return putBase64(url, json, base64).await<JsNumber>().toInt()
}

private fun jsonString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun openBlankWindow(): JsAny? = js("window.open('about:blank', '_blank')")

// Without the blank window (a popup blocker took it), opening the URL directly is the best remaining option.
private fun navigateWindow(target: JsAny?, url: String) {
    js("if (target && !target.closed) { target.location.href = url; } else { window.open(url, '_blank'); }")
}

private fun closeWindow(target: JsAny?) {
    js("if (target) target.close()")
}

@Composable
actual fun rememberUrlOpener(): (resolve: suspend () -> String?) -> Unit {
    val scope = rememberCoroutineScope()
    return remember {
        { resolve ->
            val target = openBlankWindow()
            scope.launch {
                val url = runSuspendCatching { resolve() }.getOrNull()
                if (url == null) closeWindow(target) else navigateWindow(target, url)
            }
        }
    }
}
