package com.user.idrconverter

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * محوّل الروبية الإندونيسية إلى الدينار العراقي — يعمل بدون إنترنت.
 * المستخدم يدخل قيمة 100 دولار بالعملتين، والتحويل:
 *   دينار = روبية × (قيمة 100$ بالدينار ÷ قيمة 100$ بالروبية)
 */
class MainActivity : Activity() {

    private lateinit var tabConvert: Button
    private lateinit var tabSettings: Button
    private lateinit var screenConvert: ScrollView
    private lateinit var screenSettings: ScrollView

    private lateinit var inputIdr: EditText
    private lateinit var resultIqd: TextView
    private lateinit var resultUsd: TextView
    private lateinit var rateInfo: TextView

    private lateinit var inputUsdIqd: EditText
    private lateinit var inputUsdIdr: EditText
    private lateinit var settingsInfo: TextView

    private val prefs by lazy { getSharedPreferences("rates", Context.MODE_PRIVATE) }

    private val wholeFmt = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US))
    private val usdFmt = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabConvert = findViewById(R.id.tabConvert)
        tabSettings = findViewById(R.id.tabSettings)
        screenConvert = findViewById(R.id.screenConvert)
        screenSettings = findViewById(R.id.screenSettings)
        inputIdr = findViewById(R.id.inputIdr)
        resultIqd = findViewById(R.id.resultIqd)
        resultUsd = findViewById(R.id.resultUsd)
        rateInfo = findViewById(R.id.rateInfo)
        inputUsdIqd = findViewById(R.id.inputUsdIqd)
        inputUsdIdr = findViewById(R.id.inputUsdIdr)
        settingsInfo = findViewById(R.id.settingsInfo)

        // فواصل الآلاف أثناء الكتابة
        addThousandsSeparator(inputIdr) { updateResult() }
        addThousandsSeparator(inputUsdIqd) {}
        addThousandsSeparator(inputUsdIdr) {}

        tabConvert.setOnClickListener { showConvert() }
        tabSettings.setOnClickListener { showSettings() }

        findViewById<Button>(R.id.btnZeros).setOnClickListener {
            val digits = digitsOf(inputIdr.text.toString())
            if (digits.isNotEmpty() && digits.length <= 12) inputIdr.setText(digits + "000")
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener { inputIdr.setText("") }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveRates() }

        loadRatesIntoSettings()

        // أول تشغيل بدون أسعار محفوظة → افتح واجهة الإدخال
        if (getIqdPer100() == null || getIdrPer100() == null) showSettings() else showConvert()
    }

    // ---------- التنقل بين الواجهتين ----------

    private fun showConvert() {
        screenConvert.visibility = View.VISIBLE
        screenSettings.visibility = View.GONE
        styleTab(tabConvert, true)
        styleTab(tabSettings, false)
        updateRateInfo()
        updateResult()
        inputIdr.requestFocus()
    }

    private fun showSettings() {
        screenConvert.visibility = View.GONE
        screenSettings.visibility = View.VISIBLE
        styleTab(tabConvert, false)
        styleTab(tabSettings, true)
        loadRatesIntoSettings()
    }

    private fun styleTab(b: Button, active: Boolean) {
        b.setBackgroundColor(if (active) Color.WHITE else Color.parseColor("#0A5E4E"))
        b.setTextColor(if (active) Color.parseColor("#0E7C66") else Color.WHITE)
    }

    // ---------- حفظ وقراءة الأسعار ----------

    private fun getIqdPer100(): Long? = prefs.getLong("iqd100", 0L).takeIf { it > 0 }
    private fun getIdrPer100(): Long? = prefs.getLong("idr100", 0L).takeIf { it > 0 }

    private fun loadRatesIntoSettings() {
        getIqdPer100()?.let { inputUsdIqd.setText(it.toString()) }
        getIdrPer100()?.let { inputUsdIdr.setText(it.toString()) }
        val t = prefs.getString("savedAt", null)
        settingsInfo.text = if (t != null) "آخر تحديث للأسعار: $t\nتگدر تعدّلها بأي وقت."
        else "أدخل سعر الـ 100 دولار بالعملتين حسب سعر الصرّاف، واضغط حفظ."
    }

    private fun saveRates() {
        val iqd = digitsOf(inputUsdIqd.text.toString()).toLongOrNull()
        val idr = digitsOf(inputUsdIdr.text.toString()).toLongOrNull()
        if (iqd == null || iqd <= 0 || idr == null || idr <= 0) {
            Toast.makeText(this, "أدخل القيمتين بشكل صحيح", Toast.LENGTH_SHORT).show()
            return
        }
        val now = java.text.SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(java.util.Date())
        prefs.edit().putLong("iqd100", iqd).putLong("idr100", idr).putString("savedAt", now).apply()
        hideKeyboard()
        Toast.makeText(this, "تم الحفظ ✓", Toast.LENGTH_SHORT).show()
        showConvert()
    }

    // ---------- الحساب ----------

    private fun updateResult() {
        val iqd100 = getIqdPer100()
        val idr100 = getIdrPer100()
        val idr = digitsOf(inputIdr.text.toString()).toBigDecimalOrNull() ?: BigDecimal.ZERO
        if (iqd100 == null || idr100 == null) {
            resultIqd.text = "—"
            resultUsd.text = "أدخل أسعار الصرف أولاً"
            return
        }
        val iqd = idr.multiply(BigDecimal(iqd100)).divide(BigDecimal(idr100), 0, RoundingMode.HALF_UP)
        val usd = idr.multiply(BigDecimal(100)).divide(BigDecimal(idr100), 2, RoundingMode.HALF_UP)
        resultIqd.text = "${wholeFmt.format(iqd)} د.ع"
        resultUsd.text = "≈ ${usdFmt.format(usd)} $"
    }

    private fun updateRateInfo() {
        val iqd100 = getIqdPer100()
        val idr100 = getIdrPer100()
        if (iqd100 == null || idr100 == null) {
            rateInfo.text = "لا توجد أسعار محفوظة — افتح «أسعار الصرف»."
            return
        }
        // كم دينار تساوي 10,000 روبية، وكم روبية يساوي 1,000 دينار
        val iqdPer10k = BigDecimal(10000).multiply(BigDecimal(iqd100)).divide(BigDecimal(idr100), 0, RoundingMode.HALF_UP)
        val idrPer1k = BigDecimal(1000).multiply(BigDecimal(idr100)).divide(BigDecimal(iqd100), 0, RoundingMode.HALF_UP)
        rateInfo.text = "100$ = ${wholeFmt.format(iqd100)} د.ع  |  100$ = ${wholeFmt.format(idr100)} روبية\n" +
            "10,000 روبية ≈ ${wholeFmt.format(iqdPer10k)} د.ع\n" +
            "1,000 د.ع ≈ ${wholeFmt.format(idrPer1k)} روبية"
    }

    // ---------- أدوات ----------

    private fun digitsOf(s: String): String = convertArabicDigits(s).filter { it in '0'..'9' }

    /** يحوّل الأرقام العربية-الهندية (٠١٢...) والفارسية إلى أرقام لاتينية */
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
}
