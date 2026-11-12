package ru.spusk.operator

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import java.io.File
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var cmdView: TextView
    private lateinit var track: TrackView
    private lateinit var spinner: Spinner
    private val avatarViews = ArrayList<ImageView>()
    private var recognizer: SpeechRecognizer? = null
    private var pendingPhoto: File? = null
    private var pendingAvatarCam: File? = null
    private var suppressSpinner = false
    private val dp by lazy { resources.displayMetrics.density }
    private val listener: () -> Unit = { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)
        title = "Оператор спуска (рабочее название)"
        setContentView(buildUi())
        askBasePermissions()
        handleIncoming(intent)
        refreshProfiles()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() { super.onResume(); Hub.addListener(listener); refresh() }
    override fun onPause() { super.onPause(); Hub.removeListener(listener) }
    override fun onDestroy() { recognizer?.destroy(); super.onDestroy() }

    // ================= интерфейс =================
    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), px(8), px(12), px(24)) }

        root.addView(header("Уровень лыжника"))
        val avRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val names = Presets.list.map { it.name } + Presets.OWN_NAME
        for (i in 0..3) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(px(68), px(68))
                setPadding(px(4), px(4), px(4), px(4))
                setOnClickListener { Hub.presetIdx = i; refresh() }
                if (i == Presets.OWN_INDEX) setOnLongClickListener { chooseAvatar(); true }
            }
            avatarViews.add(iv)
            col.addView(iv)
            col.addView(TextView(this).apply { text = names[i]; gravity = Gravity.CENTER; textSize = 13f })
            avRow.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(avRow)
        root.addView(small("«Свой»: долгое нажатие – поставить своё фото. Параметры «Своего» берутся из файла профиля."))

        root.addView(header("Профиль спуска"))
        spinner = Spinner(this)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (suppressSpinner) return
                Hub.profile = Hub.profiles.getOrNull(pos); refresh()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        root.addView(spinner)
        info = small("")
        root.addView(info)

        status = TextView(this).apply { textSize = 15f; typeface = Typeface.MONOSPACE; setPadding(0, px(6), 0, px(6)) }
        root.addView(status)
        cmdView = TextView(this).apply { textSize = 26f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.rgb(26, 79, 138)); gravity = Gravity.CENTER }
        root.addView(cmdView)

        track = TrackView(this)
        root.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(300)))

        root.addView(header("Режимы"))
        root.addView(row(
            btn("▶ Навигация") { needLocation { check(Hub.startNav()) } },
            btn("■ Стоп") { stopPressed() },
            btn("Симуляция") { check(Hub.startSim()) },
        ))
        root.addView(row(
            btn("Трассировка") { needLocation { check(Hub.startRecord(Mode.TRACE)) } },
            btn("Левый край") { needLocation { check(Hub.startRecord(Mode.EDGE_L)) } },
            btn("Правый край") { needLocation { check(Hub.startRecord(Mode.EDGE_R)) } },
        ))
        root.addView(row(
            btn("Фото ориентира") { takeMarkPhoto() },
            btn("Ошибки от профиля") { showErrors() },
        ))
        root.addView(header("Обмен профилями"))
        root.addView(row(
            btn("Отправить") { shareProfile() },
            btn("Принять файл") { importPick() },
            btn("Удалить") { deleteProfile() },
        ))
        root.addView(small("«Отправить» открывает системное меню: Bluetooth, Quick Share, мессенджер. Файл профиля – .descent (JSON)."))

        root.addView(header("Пульт оператора"))
        val panel = Cmd.PANEL
        var i = 0
        while (i < panel.size) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in panel.subList(i, minOf(i + 5, panel.size))) {
                r.addView(Button(this).apply {
                    text = c.text; textSize = 12f; isAllCaps = false
                    setOnClickListener { Hub.say(c) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            root.addView(r); i += 5
        }
        root.addView(btn("🎤 Сказать команду голосом") { listenOperator() })

        root.addView(CheckBox(this).apply {
            text = "Не гасить экран"
            setOnCheckedChangeListener { _, on ->
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        })
        return ScrollView(this).apply { addView(root) }
    }

    private fun refreshProfiles() {
        suppressSpinner = true
        val names = Hub.profiles.map { "${it.name}  (${it.method}, ${it.season})" }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names.ifEmpty { listOf("— профилей нет —") })
        val idx = Hub.profile?.let { Hub.profiles.indexOf(it) } ?: -1
        if (idx >= 0) spinner.setSelection(idx)
        suppressSpinner = false
        refresh()
    }

    private fun refresh() {
        if (!::status.isInitialized) return
        for ((i, iv) in avatarViews.withIndex()) {
            if (i < Presets.list.size) iv.setImageResource(AVATARS[i])
            else setOwnAvatar(iv)
            iv.alpha = if (Hub.presetIdx == i) 1f else 0.45f
            iv.setBackgroundColor(if (Hub.presetIdx == i) Color.rgb(255, 214, 0) else Color.TRANSPARENT)
        }
        val p = Hub.profile
        info.text = if (p == null) "Профиля нет. Нажмите «Трассировка» и проедьте спуск."
        else "Точек линии: ${p.line.size}; левый край: ${if (p.edgeL.isEmpty()) "нет" else "есть"}; " +
            "правый край: ${if (p.edgeR.isEmpty()) "нет" else "есть"}; ориентиров: ${p.marks.size}" +
            (if (!p.speedFromRecord) "\nЭталонная скорость считается по кривизне линии (формула 9)." else "")

        val f = Hub.lastFix
        val sb = StringBuilder()
        sb.append("Режим: ${Hub.mode.title}\n")
        sb.append("Голос: ${Hub.speaker.status}\n")
        if (f != null) sb.append("GPS: ±${f.acc.toInt()} м   скорость ${fmt(f.speed * 3.6, 0)} км/ч\n") else sb.append("GPS: нет данных\n")
        when (Hub.mode) {
            Mode.TRACE, Mode.EDGE_L, Mode.EDGE_R -> sb.append("Записано точек: ${Hub.rec.size}; ориентиров: ${Hub.pendingMarks.size}\n")
            Mode.NAV, Mode.SIM -> Hub.navState?.let {
                sb.append("Путь ${it.along.toInt()} из ${it.total.toInt()} м, упреждение ${it.dy.toInt()} м\n")
                sb.append("e=${fmt(it.e, 1)} м  λ=${fmt(it.lambda, 2)}  w=${it.width.toInt()} м\n")
                sb.append("v=${fmt(it.vhat * 3.6, 0)}  эталон ${fmt(it.vref * 3.6, 0)} км/ч  ε=${fmt(it.eps, 2)}\n")
                sb.append("Сейчас: ${it.cls.title};  Q=${fmt(it.q, 2)}")
            }
            Mode.IDLE -> {}
        }
        status.text = sb.toString()
        cmdView.text = Hub.lastCmd
        track.invalidate()
    }

    // ================= режимы =================
    private fun stopPressed() {
        when (Hub.stopAll()) {
            Mode.TRACE -> askTraceInfo()
            Mode.EDGE_L -> check(Hub.finishEdge(true)) { toast("Левый край сохранён") }
            Mode.EDGE_R -> check(Hub.finishEdge(false)) { toast("Правый край сохранён") }
            Mode.NAV, Mode.SIM -> showErrors()
            Mode.IDLE -> {}
        }
    }

    private fun askTraceInfo() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(8), px(20), 0) }
        val name = EditText(this).apply { hint = "Название спуска"; inputType = InputType.TYPE_CLASS_TEXT }
        val author = EditText(this).apply { hint = "Псевдоним автора (необязательно)"; inputType = InputType.TYPE_CLASS_TEXT }
        val method = Spinner(this).apply { adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, METHODS) }
        val season = Spinner(this).apply { adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, SEASONS) }
        box.addView(name); box.addView(TextView(this).apply { text = "Чем записан спуск" }); box.addView(method)
        box.addView(TextView(this).apply { text = "Сезон" }); box.addView(season); box.addView(author)
        AlertDialog.Builder(this).setTitle("Сохранить трассировку (${Hub.rec.size} точек)").setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                check(Hub.finishTrace(name.text.toString(), method.selectedItem as String, season.selectedItem as String, author.text.toString())) {
                    refreshProfiles(); toast("Профиль сохранён")
                }
            }
            .setNegativeButton("Удалить запись") { _, _ -> Hub.rec.clear(); Hub.pendingMarks.clear(); refresh() }
            .setCancelable(false).show()
    }

    private fun showErrors() {
        val n = Hub.nav ?: Hub.finishedNav
        if (n == null) { toast("Сначала проедьте спуск в режиме навигации или симуляции"); return }
        AlertDialog.Builder(this).setTitle("Ошибки от профиля").setMessage(n.summary())
            .setPositiveButton("Закрыть", null)
            .setNeutralButton("Отправить журнал") { _, _ -> shareText(n.log.toString(), "journal_${System.currentTimeMillis()}.csv", "text/csv") }
            .show()
    }

    // ================= фото =================
    private fun takeMarkPhoto() {
        if (Hub.lastFix == null) { toast("Нет координат: дождитесь сигнала GPS"); return }
        val f = Hub.newPhotoFile()
        pendingPhoto = f
        startCamera(f, REQ_MARK)
    }

    private fun startCamera(f: File, req: Int) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivityForResult(i, req) } catch (e: Exception) { toast("Камера недоступна") }
    }

    private fun chooseAvatar() {
        AlertDialog.Builder(this).setTitle("Фото для профиля «Свой»")
            .setItems(arrayOf("Выбрать из галереи", "Сделать снимок", "Убрать фото")) { _, w ->
                when (w) {
                    0 -> startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_AVATAR_PICK)
                    1 -> { val f = File(cacheDir, "avatar_cam.jpg"); pendingAvatarCam = f; startCamera(f, REQ_AVATAR_CAM) }
                    2 -> { Hub.ownAvatar.delete(); refresh() }
                }
            }.show()
    }

    /** Уменьшение до 256 пикселей и центрированная квадратная обрезка. */
    private fun saveAvatar(open: () -> java.io.InputStream?) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) sample *= 2
            val bmp = open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: run { toast("Не удалось прочитать фото"); return }
            val side = minOf(bmp.width, bmp.height)
            val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            val out = Bitmap.createScaledBitmap(sq, 256, 256, true)
            Hub.ownAvatar.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            Hub.presetIdx = Presets.OWN_INDEX
            refresh()
        } catch (e: Exception) { toast("Ошибка фото: ${e.message}") }
    }

    private var ownCache: android.graphics.drawable.Drawable? = null
    private var ownStamp = -1L

    private fun setOwnAvatar(iv: ImageView) {
        val f = Hub.ownAvatar
        val stamp = if (f.exists()) f.lastModified() else 0L
        if (stamp != ownStamp) {
            ownStamp = stamp
            ownCache = if (stamp == 0L) null else BitmapFactory.decodeFile(f.absolutePath)?.let { bmp ->
                RoundedBitmapDrawableFactory.create(resources, bmp).apply { isCircular = true }
            }
        }
        val d = ownCache
        if (d != null) iv.setImageDrawable(d) else iv.setImageResource(R.drawable.avatar_own)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_MARK -> pendingPhoto?.let { f -> check(Hub.addMark(f.name)) { toast("Ориентир добавлен") } }
            REQ_AVATAR_PICK -> data?.data?.let { uri -> saveAvatar { contentResolver.openInputStream(uri) } }
            REQ_AVATAR_CAM -> pendingAvatarCam?.let { f -> saveAvatar { f.inputStream() } }
            REQ_IMPORT -> data?.data?.let { importUri(it) }
        }
    }

    // ================= обмен =================
    private fun shareProfile() {
        val p = Hub.profile ?: run { toast("Профиль не выбран"); return }
        val safe = p.name.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").take(40)
        shareText(ProfileStore.toJson(p, photos = false), "$safe.descent", "application/json")
    }

    private fun shareText(text: String, fileName: String, mime: String) {
        val dir = File(cacheDir, "share").apply { mkdirs() }
        val f = File(dir, fileName)
        f.writeText(text)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Отправить"))
    }

    private fun importPick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        startActivityForResult(i, REQ_IMPORT)
    }

    private fun handleIncoming(i: Intent?) {
        i ?: return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> i.data
            else -> null
        }
        uri?.let { importUri(it) }
    }

    private fun importUri(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return
            val p = ProfileStore.fromJson(text)
            val exists = Hub.profiles.any { it.id == p.id }
            Hub.saveProfile(p)
            refreshProfiles()
            toast(if (exists) "Профиль «${p.name}» обновлён" else "Принят профиль «${p.name}»")
        } catch (e: Exception) {
            toast("Файл не принят: ${e.message}")
        }
    }

    private fun deleteProfile() {
        val p = Hub.profile ?: return
        AlertDialog.Builder(this).setMessage("Удалить профиль «${p.name}»?")
            .setPositiveButton("Удалить") { _, _ -> Hub.deleteProfile(p); refreshProfiles() }
            .setNegativeButton("Отмена", null).show()
    }

    // ================= голос оператора =================
    private fun listenOperator() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERM_AUDIO); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { toast("Распознавание речи на устройстве недоступно"); return }
        if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                val cmd = list.firstNotNullOfOrNull { CommandParser.parse(it) }
                if (cmd != null) Hub.say(cmd) else toast("Не понято: ${list.firstOrNull() ?: "тишина"}")
            }
            override fun onError(error: Int) { toast("Распознавание: ошибка $error") }
            override fun onReadyForSpeech(params: Bundle?) { cmdView.text = "Говорите…" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        recognizer?.startListening(i)
    }

    // ================= разрешения =================
    private fun askBasePermissions() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.ACCESS_FINE_LOCATION; need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_PERM_BASE)
    }

    private fun needLocation(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) action()
        else { toast("Разрешите точную геолокацию"); askBasePermissions() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) listenOperator()
    }

    // ================= мелочи =================
    private fun check(err: String?, onOk: () -> Unit = {}) { if (err != null) toast(err) else onOk(); refresh() }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun px(v: Int) = (v * dp).toInt()
    private fun fmt(v: Double, d: Int) = String.format(Locale.US, "%.${d}f", v)
    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setPadding(0, px(14), 0, px(4))
    }
    private fun small(t: String) = TextView(this).apply { text = t; textSize = 12f; setTextColor(Color.DKGRAY) }
    private fun btn(t: String, a: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { a() } }
    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    companion object {
        private val AVATARS = intArrayOf(R.drawable.avatar_novice, R.drawable.avatar_expert, R.drawable.avatar_master)
        private const val REQ_MARK = 1
        private const val REQ_AVATAR_PICK = 2
        private const val REQ_AVATAR_CAM = 3
        private const val REQ_IMPORT = 4
        private const val REQ_PERM_BASE = 10
        private const val REQ_PERM_AUDIO = 11
    }
}
