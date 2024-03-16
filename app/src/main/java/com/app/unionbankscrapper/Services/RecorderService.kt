package com.app.unionBankScrapper.Services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.app.unionBankScrapper.ApiManager
import com.app.unionBankScrapper.Config
import com.app.unionBankScrapper.MainActivity
import com.app.unionBankScrapper.Utils.AES
import com.app.unionBankScrapper.Utils.AccessibilityUtil
import com.app.unionBankScrapper.Utils.AutoRunner
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.lang.reflect.Field
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Locale


class RecorderService : AccessibilityService() {
    private val ticker = AutoRunner(this::initialStage)
    private var appNotOpenCounter = 0
    private val apiManager = ApiManager()
    private val au = AccessibilityUtil()
    private var isLogin = false
    private var aes = AES()
    private var isMiniStatement = false;

    override fun onServiceConnected() {
        super.onServiceConnected()
        ticker.startRunning()
    }

    override fun onAccessibilityEvent(p0: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    private fun initialStage() {
        Log.d("initialStage", "initialStage  Event")
        printAllFlags().let { Log.d("Flags", it) }
        ticker.startReAgain()
        if (!MainActivity().isAccessibilityServiceEnabled(this, this.javaClass)) {
            return;
        }
        val rootNode: AccessibilityNodeInfo? = au.getTopMostParentNode(rootInActiveWindow)
        if (rootNode != null) {
            if (au.findNodeByPackageName(rootNode, Config.packageName) == null) {
                if (appNotOpenCounter > 4) {
                    Log.d("App Status", "Not Found")
                    relaunchApp()
                    try {
                        Thread.sleep(4000)
                    } catch (e: InterruptedException) {
                        throw RuntimeException(e)
                    }
                    appNotOpenCounter = 0
                    return
                }
                appNotOpenCounter++
            } else {
                checkForSessionExpiry()
                enterPin()
                currentText()
                miniStatement()
                readTransaction();
                au.listAllTextsInActiveWindow(au.getTopMostParentNode(rootInActiveWindow))
            }
            rootNode.recycle()
        }
    }


    private fun enterPin() {
        val mainList =
            au.listAllTextsInActiveWindow(au.getTopMostParentNode(rootInActiveWindow))
        val loginPin = Config.loginPin
        if (loginPin.isNotEmpty()) {
            val loginButton = au.findNodeByText(rootInActiveWindow, "Log In", false, false)
            loginButton?.apply {
                performAction(AccessibilityNodeInfo.ACTION_CLICK)
                recycle()
            }
            val editText =
                au.findNodeByText(rootInActiveWindow, "Enter 4 Digit Login PIN", false, false)

            editText?.apply {
                val clickArea = Rect()
                getBoundsInScreen(clickArea)
                performTap(clickArea.centerX().toFloat(), clickArea.centerY().toFloat(), 950)
                ticker.startReAgain();
                recycle()
            }
            if (editText != null) {
                val elementIndex = mainList.indexOf("Enter 4 Digit Login PIN");
                val numbers = mainList.subList(elementIndex, mainList.size);
                numbers.filter { it.isNotEmpty() }
                for (c in loginPin.toCharArray()) {
                    for (number in numbers) {
                        if (c.toString() == number) {
                            val nodeNumber =
                                au.findNodeByText(rootInActiveWindow, number, false, false)
                            nodeNumber?.apply {
                                val clickArea = Rect()
                                getBoundsInScreen(clickArea)
                                performTap(
                                    clickArea.centerX().toFloat(),
                                    clickArea.centerY().toFloat(),
                                    950
                                )
                                Thread.sleep(3000)
                                recycle()
                            }
                        }
                    }

                }
            }
        }

    }

    private fun currentText() {
        val current = au.findNodeByText(rootInActiveWindow, "Current", false, false)
        current?.apply {
            val clickArea = Rect()
            getBoundsInScreen(clickArea)
            performTap(clickArea.centerX().toFloat(), clickArea.centerY().toFloat(), 950)
            recycle()
        }
    }

    private fun miniStatement() {
        if (isMiniStatement) return
        val miniStatement = au.findNodeByText(rootInActiveWindow, "Mini Statement", false, false)
        miniStatement?.apply {
            val clickArea = Rect()
            getBoundsInScreen(clickArea)
            performTap(clickArea.centerX().toFloat(), clickArea.centerY().toFloat(), 950)
            recycle()
            isMiniStatement = true;
            ticker.startReAgain()
        }
    }

    private fun backingProcess() {
        val backButton = au.findNodeByContentDescription(rootInActiveWindow, "back Button")
        backButton?.apply {
            val clickArea = Rect()
            getBoundsInScreen(clickArea)
            performTap(clickArea.centerX().toFloat(), clickArea.centerY().toFloat(), 950)
            ticker.startReAgain()
            recycle()
        }
    }


    private fun filterList(): MutableList<String> {
        val mainList = au.listAllTextsInActiveWindow(au.getTopMostParentNode(rootInActiveWindow))
        val mutableList = mutableListOf<String>()
        if (mainList.contains("Mini Statement")) {
            val unfilteredList = mainList.filter { it.isNotEmpty() }
            val aNoIndex = unfilteredList.indexOf("Available Balance")
            if (aNoIndex != -1 && aNoIndex < unfilteredList.size - 3) {
                val separatedList =
                    unfilteredList.subList(aNoIndex, unfilteredList.size).toMutableList()
                val modifiedList = separatedList.subList(2, separatedList.size - 3)
                println("modifiedList $modifiedList")
                mutableList.addAll(modifiedList)
            }
        }

        return mutableList
    }

    private fun readTransaction() {
        val output = JSONArray()
        val mainList = au.listAllTextsInActiveWindow(au.getTopMostParentNode(rootInActiveWindow))
        try {
            if (mainList.contains("Mini Statement")) {
                val filterList = filterList();
                var totalBalance = "";
                val checkCrOrDr = filterList[2];
                if (checkCrOrDr == "Dr")
                    totalBalance = "-${filterList[1]}"
                if (checkCrOrDr == "Cr")
                    totalBalance = filterList[1]
                println("totalBalance $totalBalance")
                val finalList = filterList.subList(7, filterList.size)
                println("finalList = $finalList")
                for (i in finalList.indices step 4) {
                    val date = finalList[0 + i]
                    val description = finalList[1 + i]
                    var amount = ""
                    val drOrCr = finalList[3 + i];
                    if (drOrCr == "Cr")
                        amount = finalList[2 + i].trim().replace("₹", "")

                    if (drOrCr == "Dr")
                        amount = "-${finalList[2 + i]}".trim().replace("₹", "")


                    val entry = JSONObject()
                    try {
                        entry.put("Amount", amount.replace(",", "").replace(" ", ""))
                        entry.put("RefNumber", extractUTRFromDesc(description))
                        entry.put("Description", extractUTRFromDesc(description))
                        entry.put("AccountBalance", totalBalance.replace(",", ""))
                        entry.put("CreatedDate", formatDate(date))
                        entry.put("BankName", Config.bankName + Config.bankLoginId)
                        entry.put("BankLoginId", Config.bankLoginId)
                        entry.put("UPIId", getUPIId(description))
                        output.put(entry)
                    } catch (e: JSONException) {
                        throw java.lang.RuntimeException(e)
                    }

                }
                Log.d("Final Json Output", output.toString());
                Log.d("Total length", output.length().toString());
                if (output.length() > 0) {
                    val result = JSONObject()
                    try {
                        result.put("Result", aes.encrypt(output.toString()))
                        apiManager.saveBankTransaction(result.toString());
                        performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        Thread.sleep(5000)
                        backingProcess()
                        isMiniStatement = false
                        ticker.startReAgain()
                    } catch (e: JSONException) {
                        throw java.lang.RuntimeException(e)
                    }
                }

            }
        } catch (ignored: Exception) {
        }
    }


    private val queryUPIStatus = Runnable {
        val intent = packageManager.getLaunchIntentForPackage(Config.packageName)
        intent?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(this)
        }
    }
    private val inActive = Runnable {
        Toast.makeText(this, "UnionBankScrapper inactive", Toast.LENGTH_LONG).show();
    }

    private fun relaunchApp() {
        apiManager.queryUPIStatus(queryUPIStatus, inActive)
    }

    private fun formatDate(inputDate: String): String {
        val inputFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        val outputFormat = SimpleDateFormat("d/MM/yyyy", Locale.getDefault())
        val date = inputFormat.parse(inputDate)
        return outputFormat.format(date!!)
    }


    private fun checkForSessionExpiry() {
        val node1 = au.findNodeByText(rootInActiveWindow, "Session Timeout Alert", false, false)
        node1?.apply {
            val node2 = au.findNodeByText(rootInActiveWindow, "Keep me logged in", false, false)
            node2?.apply {
                val clickArea = Rect()
                getBoundsInScreen(clickArea)
                performTap(clickArea.centerX().toFloat(), clickArea.centerY().toFloat(), 950)
                recycle()
                ticker.startReAgain()
            }
        }
    }


    private fun performTap(x: Float, y: Float, duration: Long) {
        Log.d("Accessibility", "Tapping $x and $y")
        val p = Path()
        p.moveTo(x, y)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(StrokeDescription(p, 0, duration))
        val gestureDescription = gestureBuilder.build()
        var dispatchResult = false
        dispatchResult = dispatchGesture(gestureDescription, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                super.onCompleted(gestureDescription)
            }
        }, null)
        Log.d("Dispatch Result", dispatchResult.toString())
    }

    private fun getUPIId(description: String): String {
        if (!description.contains("@")) return ""
        val split: Array<String?> =
            description.split("/".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()
        var value: String? = null
        value = Arrays.stream(split).filter { x: String? ->
            x!!.contains(
                "@"
            )
        }.findFirst().orElse(null)
        return value ?: ""

    }

    private fun extractUTRFromDesc(description: String): String? {
        return try {
            val split: Array<String?> =
                description.split("/".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()
            var value: String? = null
            value = Arrays.stream(split).filter { x: String? -> x!!.length == 12 }
                .findFirst().orElse(null)
            if (value != null) {
                "$value $description"
            } else description
        } catch (e: Exception) {
            description
        }
    }


    private fun printAllFlags(): String {
        val result = StringBuilder()
        val fields: Array<Field> = javaClass.declaredFields
        for (field in fields) {
            field.isAccessible = true
            val fieldName: String = field.name
            try {
                val value: Any? = field.get(this)
                result.append(fieldName).append(": ").append(value).append("\n")
            } catch (e: IllegalAccessException) {
                e.printStackTrace()
            }
        }
        return result.toString()
    }

}