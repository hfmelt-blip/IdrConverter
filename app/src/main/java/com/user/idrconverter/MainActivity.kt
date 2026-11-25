package com.user.idrconverter

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * محوّل العملات إلى الدينار العراقي — يعمل بدون إنترنت.
 * الدينار ثابت، والعملة الأخرى يختارها المستخدم (بحث بالبلد أو إدخال يدوي).
 * دينار = مبلغ × (قيمة 100$ بالدينار ÷ قيمة 100$ بالعملة الأخرى)
 * سعر كل عملة يُحفظ لوحده، فالرجوع لعملة سابقة يرجّع سعرها.
 */
class MainActivity : Activity() {

    data class Entry(val country: String, val countryEn: String, val currency: String, val code: String) {
        val display get() = "$country — $currency ($code)"
        val searchText by lazy { MainActivity.norm("$country $countryEn $currency $code") }
    }

    private lateinit var tabConvert: Button
    private lateinit var tabSettings: Button
    private lateinit var screenConvert: ScrollView
    private lateinit var screenSettings: ScrollView
    private lateinit var appTitle: TextView

    private lateinit var labelInput: TextView
    private lateinit var inputOther: EditText
    private lateinit var resultIqd: TextView
    private lateinit var resultUsd: TextView
    private lateinit var rateInfo: TextView

    private lateinit var currencyName: TextView
    private lateinit var labelRate2: TextView
    private lateinit var inputUsdIqd: EditText
    private lateinit var inputUsdOther: EditText
    private lateinit var settingsInfo: TextView

    private val prefs by lazy { getSharedPreferences("rates", Context.MODE_PRIVATE) }
    private val entries: List<Entry> by lazy { loadEntries() }

    private val wholeFmt = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US))
    private val usdFmt = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))

    // العملة المختارة حالياً
    private var curName = ""
    private var curCode = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabConvert = findViewById(R.id.tabConvert)
        tabSettings = findViewById(R.id.tabSettings)
        screenConvert = findViewById(R.id.screenConvert)
        screenSettings = findViewById(R.id.screenSettings)
        appTitle = findViewById(R.id.appTitle)
        labelInput = findViewById(R.id.labelInput)
        inputOther = findViewById(R.id.inputIdr)
        resultIqd = findViewById(R.id.resultIqd)
        resultUsd = findViewById(R.id.resultUsd)
        rateInfo = findViewById(R.id.rateInfo)
        currencyName = findViewById(R.id.currencyName)
        labelRate2 = findViewById(R.id.labelRate2)
        inputUsdIqd = findViewById(R.id.inputUsdIqd)
        inputUsdOther = findViewById(R.id.inputUsdIdr)
        settingsInfo = findViewById(R.id.settingsInfo)

        migrateOldVersion()
        curName = prefs.getString("curName", "") ?: ""
        curCode = prefs.getString("curCode", "") ?: ""

        addThousandsSeparator(inputOther) { updateResult() }
        addThousandsSeparator(inputUsdIqd) {}
        addThousandsSeparator(inputUsdOther) {}

        tabConvert.setOnClickListener { showConvert() }
        tabSettings.setOnClickListener { showSettings() }

        findViewById<Button>(R.id.btnZeros).setOnClickListener {
            val digits = digitsOf(inputOther.text.toString())
            if (digits.isNotEmpty() && digits.length <= 12) inputOther.setText(digits + "000")
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener { inputOther.setText("") }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveRates() }
        findViewById<Button>(R.id.btnChooseCurrency).setOnClickListener { showCurrencyPicker() }

        refreshLabels()
        if (curName.isEmpty() || getIqdPer100() == null || getOtherPer100() == null) showSettings() else showConvert()
        if (curName.isEmpty()) showCurrencyPicker()
    }

    /** النسخة الأولى كانت للروبية فقط — ننقل أسعارها للنظام الجديد */
    private fun migrateOldVersion() {
        val oldIdr = prefs.getLong("idr100", 0L)
        if (oldIdr > 0 && !prefs.contains("curCode")) {
            prefs.edit()
                .putString("curName", "روبية إندونيسية").putString("curCode", "IDR")
                .putLong("rate_IDR", oldIdr).remove("idr100").apply()
        }
    }

    // ---------- التنقل ----------

    private fun showConvert() {
        screenConvert.visibility = View.VISIBLE
        screenSettings.visibility = View.GONE
        styleTab(tabConvert, true); styleTab(tabSettings, false)
        updateRateInfo(); updateResult()
        inputOther.requestFocus()
    }

    private fun showSettings() {
        screenConvert.visibility = View.GONE
        screenSettings.visibility = View.VISIBLE
        styleTab(tabConvert, false); styleTab(tabSettings, true)
        loadRatesIntoSettings()
    }

    private fun styleTab(b: Button, active: Boolean) {
        b.setBackgroundColor(if (active) Color.WHITE else Color.parseColor("#0A5E4E"))
        b.setTextColor(if (active) Color.parseColor("#0E7C66") else Color.WHITE)
    }

    private fun refreshLabels() {
        val n = curName.ifEmpty { "العملة الأخرى" }
        val codePart = if (curCode.isNotEmpty() && curCode != curName) " ($curCode)" else ""
        appTitle.text = if (curName.isEmpty()) "محوّل العملات" else "محوّل $n ← دينار"
        currencyName.text = if (curName.isEmpty()) "لم تُختر بعد" else "$n$codePart"
        labelInput.text = "السعر بـ$n$codePart"
        labelRate2.text = "قيمة 100 دولار بـ$n"
    }

    // ---------- اختيار العملة ----------

    private fun showCurrencyPicker() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val search = EditText(this).apply {
            hint = "ابحث باسم البلد أو العملة (مثال: بريطانيا)"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        val list = ListView(this)
        box.addView(search)
        box.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            (360 * resources.displayMetrics.density).toInt()))

        var shown = entries
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown.map { it.display }.toMutableList())
        list.adapter = adapter

        val dialog = AlertDialog.Builder(this)
            .setTitle("اختر العملة")
            .setView(box)
            .setNeutralButton("إدخال يدوي") { _, _ -> showManualCurrency() }
            .setNegativeButton("إلغاء", null)
            .create()

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = norm(s?.toString() ?: "")
                shown = if (q.isEmpty()) entries else entries.filter { e -> q.split(' ').all { it in e.searchText } }
                adapter.clear()
                adapter.addAll(shown.map { it.display })
                adapter.notifyDataSetChanged()
            }
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            val e = shown[pos]
            setCurrency(e.currency, e.code)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showManualCurrency() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val name = EditText(this).apply { hint = "اسم العملة (مثال: روبية إندونيسية)"; isSingleLine = true }
        val code = EditText(this).apply {
            hint = "رمز العملة — اختياري (مثال: IDR)"; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        box.addView(name); box.addView(code)
        AlertDialog.Builder(this)
            .setTitle("إدخال العملة يدوياً")
            .setView(box)
            .setPositiveButton("تم") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) {
                    Toast.makeText(this, "اكتب اسم العملة", Toast.LENGTH_SHORT).show()
                } else {
                    setCurrency(n, code.text.toString().trim().uppercase(Locale.US))
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun setCurrency(name: String, code: String) {
        curName = name
        curCode = code
        prefs.edit().putString("curName", name).putString("curCode", code).apply()
        refreshLabels()
        loadRatesIntoSettings()
        inputOther.setText("")
        updateRateInfo(); updateResult()
        if (getOtherPer100() == null) {
            showSettings()
            inputUsdOther.requestFocus()
        }
    }

    private fun loadEntries(): List<Entry> =
        resources.openRawResource(R.raw.currencies).bufferedReader(Charsets.UTF_8).readLines()
            .mapNotNull { line ->
                val p = line.split('|')
                if (p.size == 4) Entry(p[0].trim(), p[1].trim(), p[2].trim(), p[3].trim()) else null
            }

    // ---------- حفظ وقراءة الأسعار ----------

    private fun rateKey() = "rate_" + curCode.ifEmpty { curName }

    private fun getIqdPer100(): Long? = prefs.getLong("iqd100", 0L).takeIf { it > 0 }
    private fun getOtherPer100(): Long? =
        if (curName.isEmpty()) null else prefs.getLong(rateKey(), 0L).takeIf { it > 0 }

    private fun loadRatesIntoSettings() {
        inputUsdIqd.setText(getIqdPer100()?.toString() ?: "")
        inputUsdOther.setText(getOtherPer100()?.toString() ?: "")
        val t = prefs.getString("savedAt", null)
        settingsInfo.text = when {
            curName.isEmpty() -> "اختر العملة أولاً من زر «تغيير العملة»."
            t != null -> "آخر تحديث للأسعار: $t\nتگدر تعدّلها بأي وقت، وكل عملة يُحفظ سعرها لوحدها."
            else -> "أدخل سعر الـ 100 دولار بالعملتين حسب سعر الصرّاف، واضغط حفظ."
        }
    }

    private fun saveRates() {
        if (curName.isEmpty()) {
            Toast.makeText(this, "اختر العملة أولاً", Toast.LENGTH_SHORT).show()
            showCurrencyPicker(); return
        }
        val iqd = digitsOf(inputUsdIqd.text.toString()).toLongOrNull()
        val other = digitsOf(inputUsdOther.text.toString()).toLongOrNull()
        if (iqd == null || iqd <= 0 || other == null || other <= 0) {
            Toast.makeText(this, "أدخل القيمتين بشكل صحيح", Toast.LENGTH_SHORT).show()
            return
        }
        val now = java.text.SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(java.util.Date())
        prefs.edit().putLong("iqd100", iqd).putLong(rateKey(), other).putString("savedAt", now).apply()
        hideKeyboard()
        Toast.makeText(this, "تم الحفظ ✓", Toast.LENGTH_SHORT).show()
        showConvert()
    }

    // ---------- الحساب ----------

    private fun updateResult() {
        val iqd100 = getIqdPer100()
        val oth100 = getOtherPer100()
        val amount = digitsOf(inputOther.text.toString()).toBigDecimalOrNull() ?: BigDecimal.ZERO
        if (iqd100 == null || oth100 == null) {
            resultIqd.text = "—"
            resultUsd.text = "أدخل أسعار الصرف أولاً"
            return
        }
        val iqd = amount.multiply(BigDecimal(iqd100)).divide(BigDecimal(oth100), 0, RoundingMode.HALF_UP)
        val usd = amount.multiply(BigDecimal(100)).divide(BigDecimal(oth100), 2, RoundingMode.HALF_UP)
        resultIqd.text = "${wholeFmt.format(iqd)} د.ع"
        resultUsd.text = "≈ ${usdFmt.format(usd)} $"
    }

    private fun updateRateInfo() {
        val iqd100 = getIqdPer100()
        val oth100 = getOtherPer100()
        if (iqd100 == null || oth100 == null) {
            rateInfo.text = "لا توجد أسعار محفوظة لهذه العملة — افتح «أسعار الصرف»."
            return
        }
        val n = curName
        // مرجع سريع بأرقام مناسبة لحجم العملة
        val unit = BigDecimal(oth100).divide(BigDecimal(100)).let { perUsd ->
            when {
                perUsd >= BigDecimal(1000) -> 10000L
                perUsd >= BigDecimal(10) -> 100L
                else -> 1L
            }
        }
        val iqdPerUnit = BigDecimal(unit).multiply(BigDecimal(iqd100)).divide(BigDecimal(oth100), 0, RoundingMode.HALF_UP)
        val othPer1k = BigDecimal(1000).multiply(BigDecimal(oth100)).divide(BigDecimal(iqd100), 2, RoundingMode.HALF_UP)
        val othFmt = if (othPer1k >= BigDecimal(100)) wholeFmt.format(othPer1k) else usdFmt.format(othPer1k)
        rateInfo.text = "100$ = ${wholeFmt.format(iqd100)} د.ع  |  100$ = ${wholeFmt.format(oth100)} $n\n" +
            "${wholeFmt.format(unit)} $n ≈ ${wholeFmt.format(iqdPerUnit)} د.ع\n" +
            "1,000 د.ع ≈ $othFmt $n"
    }

    // ---------- أدوات ----------

    private fun digitsOf(s: String): String = convertArabicDigits(s).filter { it in '0'..'9' }

    private fun convertArabicDigits(s: String): String = buildString {
        for (c in s) {
            append(
                when (c) {
                    in '٠'..'٩' -> '0' + (c - '٠')
                    in '۰'..'۹' -> '0' + (c - '۰')
                    else -> c
                }
            )
        }
    }

    private fun addThousandsSeparator(et: EditText, onChange: () -> Unit) {
        et.addTextChangedListener(object : TextWatcher {
            private var busy = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (busy || s == null) return
                busy = true
                val digits = digitsOf(s.toString()).trimStart('0')
                val formatted = if (digits.isEmpty()) "" else wholeFmt.format(BigDecimal(digits))
                if (formatted != s.toString()) {
                    et.setText(formatted)
                    et.setSelection(formatted.length)
                }
                busy = false
                onChange()
            }
        })
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    companion object {
        /** توحيد النص للبحث: حروف صغيرة، وتوحيد الألف والتاء المربوطة والياء */
        fun norm(s: String): String = s.lowercase(Locale.ROOT)
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ة', 'ه').replace('ى', 'ي')
            .replace("ـ", "").replace("(", " ").replace(")", " ")
            .replace(Regex("\\s+"), " ").trim()
    }
}
