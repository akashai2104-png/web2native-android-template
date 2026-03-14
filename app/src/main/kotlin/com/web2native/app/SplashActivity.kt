package com.web2native.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

// TEMPLATE_MARKER:SplashActivity_v8
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val splashText = BuildConfig.SPLASH_TEXT
        val splashDesign = try { BuildConfig.SPLASH_DESIGN } catch (_: Exception) { "dots" }

        Log.i("SPLASH_CONFIG", "=== SPLASH BUILDCONFIG ===")
        Log.i("SPLASH_CONFIG", "SPLASH_TEXT=" + splashText)
        Log.i("SPLASH_CONFIG", "SPLASH_DESIGN=" + splashDesign)
        Log.i("SPLASH_CONFIG", "SPLASH_ANIMATION=" + BuildConfig.SPLASH_ANIMATION)
        try { Log.i("SPLASH_CONFIG", "SPLASH_COLOR=" + BuildConfig.SPLASH_COLOR) } catch (_: Exception) { Log.i("SPLASH_CONFIG", "SPLASH_COLOR=MISSING") }
        Log.i("SPLASH_CONFIG", "=== END SPLASH CONFIG ===")

        val shouldShowSplash = splashText.isNotEmpty() || (splashDesign != "none" && splashDesign.isNotEmpty())
        Log.i("SPLASH_CONFIG", "shouldShowSplash=" + shouldShowSplash)

        if (shouldShowSplash) {
            // Compute background color BEFORE setContentView so we can apply it everywhere
            val splashColorStr = try { BuildConfig.SPLASH_COLOR } catch (_: Exception) { "" }
            val themeColor = try {
                Color.parseColor(BuildConfig.THEME_COLOR)
            } catch (_: Exception) {
                Color.parseColor("#1D4ED8")
            }
            val bgColor = if (splashColorStr.isNotEmpty()) {
                try { Color.parseColor(splashColorStr) } catch (_: Exception) { themeColor }
            } else {
                themeColor
            }

            Log.i("SPLASH_CONFIG", "bgColor=#" + Integer.toHexString(bgColor))

            // Apply background to window BEFORE inflating layout to avoid white flash
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = bgColor
            window.navigationBarColor = bgColor
            window.setBackgroundDrawable(ColorDrawable(bgColor))

            setContentView(R.layout.activity_splash)

            // Apply background to ALL layers to ensure no white bleeds through
            findViewById<View>(android.R.id.content).setBackgroundColor(bgColor)
            val rootView = findViewById<View>(R.id.splashRoot)
            rootView?.setBackgroundColor(bgColor)

            val iconView = findViewById<ImageView>(R.id.splashIcon)
            val textView = findViewById<TextView>(R.id.splashText)
            val circularProgress = findViewById<ProgressBar>(R.id.splashProgress)
            val horizontalProgress = findViewById<ProgressBar>(R.id.splashProgressBar)
            val dotsContainer = findViewById<LinearLayout>(R.id.splashDots)
            val dots = listOf(
                findViewById<View>(R.id.splashDot1),
                findViewById<View>(R.id.splashDot2),
                findViewById<View>(R.id.splashDot3),
            )

            if (splashText.isNotEmpty()) {
                textView.text = splashText
                textView.visibility = View.VISIBLE
            } else {
                textView.visibility = View.GONE
            }

            val textColor = getContrastColor(bgColor)
            textView.setTextColor(textColor)

            val dotColor = Color.argb(180, Color.red(textColor), Color.green(textColor), Color.blue(textColor))
            dots.forEach { dot ->
                dot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(dotColor)
                }
            }

            circularProgress.visibility = View.GONE
            horizontalProgress.visibility = View.GONE
            dotsContainer.visibility = View.GONE

            when (splashDesign) {
                "none" -> {
                    // No loading indicator
                }
                "spinner" -> {
                    circularProgress.isIndeterminate = true
                    circularProgress.visibility = View.VISIBLE
                }
                "bar" -> {
                    horizontalProgress.isIndeterminate = true
                    horizontalProgress.visibility = View.VISIBLE
                }
                else -> {
                    dotsContainer.visibility = View.VISIBLE
                    dots.forEachIndexed { index, dot ->
                        startDotAnimation(dot, (index * 120L))
                    }
                }
            }

            iconView.alpha = 0f
            iconView.scaleX = 0.3f
            iconView.scaleY = 0.3f
            textView.alpha = 0f
            textView.translationY = 30f
            circularProgress.alpha = 0f
            horizontalProgress.alpha = 0f
            dotsContainer.alpha = 0f

            val animationStyle = BuildConfig.SPLASH_ANIMATION

            val activeIndicator: View? = when (splashDesign) {
                "bar" -> horizontalProgress
                "spinner" -> circularProgress
                "none" -> null
                else -> dotsContainer
            }

            when (animationStyle) {
                "fade" -> {
                    val iconAlpha = ObjectAnimator.ofFloat(iconView, "alpha", 0f, 1f).apply { duration = 800 }
                    val textAlpha = ObjectAnimator.ofFloat(textView, "alpha", 0f, 1f).apply { duration = 800; startDelay = 200 }
                    val textSlide = ObjectAnimator.ofFloat(textView, "translationY", 0f, 0f).apply { duration = 1 }
                    val anims = mutableListOf(iconAlpha, textAlpha, textSlide)
                    activeIndicator?.let {
                        anims.add(ObjectAnimator.ofFloat(it, "alpha", 0f, 1f).apply { startDelay = 600; duration = 300 })
                    }
                    AnimatorSet().apply { playTogether(anims.toList()); start() }
                }
                "slide-up" -> {
                    val iconAlpha = ObjectAnimator.ofFloat(iconView, "alpha", 0f, 1f).apply { duration = 600 }
                    val iconSlide = ObjectAnimator.ofFloat(iconView, "translationY", 200f, 0f).apply {
                        duration = 600; interpolator = AccelerateDecelerateInterpolator()
                    }
                    val textAlpha = ObjectAnimator.ofFloat(textView, "alpha", 0f, 1f).apply { startDelay = 150; duration = 600 }
                    val textSlide = ObjectAnimator.ofFloat(textView, "translationY", 150f, 0f).apply {
                        startDelay = 150; duration = 600; interpolator = AccelerateDecelerateInterpolator()
                    }
                    val anims = mutableListOf(iconAlpha, iconSlide, textAlpha, textSlide)
                    activeIndicator?.let {
                        anims.add(ObjectAnimator.ofFloat(it, "alpha", 0f, 1f).apply { startDelay = 600; duration = 300 })
                    }
                    AnimatorSet().apply { playTogether(anims.toList()); start() }
                }
                "bounce" -> {
                    iconView.translationY = -300f
                    val iconAlpha = ObjectAnimator.ofFloat(iconView, "alpha", 0f, 1f).apply { duration = 400 }
                    val iconDrop = ObjectAnimator.ofFloat(iconView, "translationY", -300f, 0f).apply {
                        duration = 700; interpolator = OvershootInterpolator(2.0f)
                    }
                    val iconScaleX = ObjectAnimator.ofFloat(iconView, "scaleX", 0.3f, 1f).apply { duration = 500 }
                    val iconScaleY = ObjectAnimator.ofFloat(iconView, "scaleY", 0.3f, 1f).apply { duration = 500 }
                    val textAlpha = ObjectAnimator.ofFloat(textView, "alpha", 0f, 1f).apply { startDelay = 500; duration = 400 }
                    val textSlide = ObjectAnimator.ofFloat(textView, "translationY", 30f, 0f).apply { startDelay = 500; duration = 400 }
                    val anims = mutableListOf(iconAlpha, iconDrop, iconScaleX, iconScaleY, textAlpha, textSlide)
                    activeIndicator?.let {
                        anims.add(ObjectAnimator.ofFloat(it, "alpha", 0f, 1f).apply { startDelay = 700; duration = 300 })
                    }
                    AnimatorSet().apply { playTogether(anims.toList()); start() }
                }
                else -> {
                    val iconScaleX = ObjectAnimator.ofFloat(iconView, "scaleX", 0.3f, 1f).apply {
                        duration = 500; interpolator = OvershootInterpolator(1.5f)
                    }
                    val iconScaleY = ObjectAnimator.ofFloat(iconView, "scaleY", 0.3f, 1f).apply {
                        duration = 500; interpolator = OvershootInterpolator(1.5f)
                    }
                    val iconAlpha = ObjectAnimator.ofFloat(iconView, "alpha", 0f, 1f).apply { duration = 400 }
                    val textAlpha = ObjectAnimator.ofFloat(textView, "alpha", 0f, 1f).apply {
                        startDelay = 300; duration = 400; interpolator = AccelerateDecelerateInterpolator()
                    }
                    val textSlide = ObjectAnimator.ofFloat(textView, "translationY", 30f, 0f).apply {
                        startDelay = 300; duration = 400; interpolator = AccelerateDecelerateInterpolator()
                    }
                    val anims = mutableListOf(iconScaleX, iconScaleY, iconAlpha, textAlpha, textSlide)
                    activeIndicator?.let {
                        anims.add(ObjectAnimator.ofFloat(it, "alpha", 0f, 1f).apply { startDelay = 600; duration = 300 })
                    }
                    AnimatorSet().apply { playTogether(anims.toList()); start() }
                }
            }

            Handler(Looper.getMainLooper()).postDelayed({
                startMainActivity()
            }, 2500)
        } else {
            startMainActivity()
        }
    }

    private fun startDotAnimation(dot: View, startDelayMs: Long) {
        dot.alpha = 0.55f

        ObjectAnimator.ofFloat(dot, "translationY", 0f, -10f, 0f).apply {
            duration = 600
            startDelay = startDelayMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        ObjectAnimator.ofFloat(dot, "alpha", 0.55f, 1f, 0.55f).apply {
            duration = 600
            startDelay = startDelayMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun getContrastColor(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return if (luminance > 0.5) Color.parseColor("#1E293B") else Color.WHITE
    }

    private fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
