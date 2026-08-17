package com.idk.touchoverlay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidServiceRunning = false
    private lateinit var controlView: ControlOverlayView
    private lateinit var statusView: TextView
    private lateinit var settingsButton: Button

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            performExport()
        } else {
            Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            performExport()
        } else {
            Toast.makeText(this, "Full storage access not granted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val btManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(BluetoothManager::class.java)
        } else {
            null
        }
        bluetoothAdapter = btManager?.adapter ?: (getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? BluetoothAdapter)
        
        setContentView(buildMainScreen())
        startHIDService()
    }

    private fun buildMainScreen(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Main control view (fullscreen)
        controlView = ControlOverlayView(this).apply {
            editMode = false
            elements = ControlsRepository.load(this@MainActivity)
            onLayoutChanged = { ControlsRepository.save(this@MainActivity, elements) }
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        root.addView(controlView)

        // Top bar with status & settings
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(200, 20, 20, 20))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 56).apply {
                gravity = Gravity.TOP
            }
        }

        statusView = TextView(this).apply {
            text = "BT Gamepad: Ready"
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(16, 0, 16, 0)
            }
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(statusView)

        settingsButton = Button(this).apply {
            text = "⚙ Settings"
            textSize = 12f
            setPadding(8, 4, 8, 4)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 8
            }
            setOnClickListener { showSettings() }
        }
        topBar.addView(settingsButton)

        root.addView(topBar)

        return root
    }

    private fun showSettings() {
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(240, 30, 30, 30))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val settingsScroll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        settingsScroll.addView(TextView(this).apply {
            text = "⚙ Settings"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        })

        settingsScroll.addView(TextView(this).apply {
            text = "Stick Deadzone"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 8)
        })

        val currentDeadzonePercent = (ControlsRepository.load(this)
            .firstOrNull { it.type == ElementType.JOYSTICK_GAMEPAD }?.deadzonePercent ?: 0.08f)
            .times(100).toInt().coerceIn(0, 50)

        val deadzoneLabel = TextView(this).apply {
            text = "$currentDeadzonePercent%"
            textSize = 12f
            setTextColor(Color.GRAY)
        }
        settingsScroll.addView(deadzoneLabel)

        settingsScroll.addView(SeekBar(this).apply {
            max = 50
            progress = currentDeadzonePercent
            setPadding(0, 0, 0, 16)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    deadzoneLabel.text = "$progress%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    val percent = seekBar.progress / 100f
                    ControlsRepository.setStickDeadzone(this@MainActivity, percent)
                }
            })
        })

        val gyroEnabled = ControlsRepository.isGyroEnabled(this)
        settingsScroll.addView(Button(this).apply {
            text = if (gyroEnabled) "Gyro: ON" else "Gyro: OFF"
            setPadding(16, 8, 16, 8)
            setOnClickListener {
                val enabled = !ControlsRepository.isGyroEnabled(this@MainActivity)
                ControlsRepository.setGyroEnabled(this@MainActivity, enabled)
                text = if (enabled) "Gyro: ON" else "Gyro: OFF"
            }
        })

        settingsScroll.addView(Button(this).apply {
            text = "Edit Layout"
            setPadding(16, 8, 16, 8)
            setOnClickListener { showLayoutEditor(container) }
        })

        settingsScroll.addView(Button(this).apply {
            text = "Reset Layout"
            setPadding(16, 8, 16, 8)
            setOnClickListener {
                ControlsRepository.save(this@MainActivity, ControlsRepository.defaultLayout())
                controlView.elements = ControlsRepository.load(this@MainActivity)
                controlView.invalidate()
                Toast.makeText(this@MainActivity, "Layout reset", Toast.LENGTH_SHORT).show()
            }
        })

        settingsScroll.addView(Button(this).apply {
            text = "Export Layout"
            setPadding(16, 8, 16, 8)
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!Environment.isExternalStorageManager()) {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                        manageStorageLauncher.launch(intent)
                        return@setOnClickListener
                    }
                } else {
                    if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        return@setOnClickListener
                    }
                }
                performExport()
            }
        })

        settingsScroll.addView(Button(this).apply {
            text = "Load Layout"
            setPadding(16, 8, 16, 8)
            setOnClickListener {
                val path = java.io.File(Environment.getExternalStorageDirectory(), "OverlayControls/TouchOverlayLayout.json")
                val loaded = ControlsRepository.loadFromFile(this@MainActivity, path.absolutePath)
                if (loaded) {
                    controlView.elements = ControlsRepository.load(this@MainActivity)
                    controlView.invalidate()
                    Toast.makeText(this@MainActivity, "Layout loaded", Toast.LENGTH_SHORT).show()
                    setContentView(buildMainScreen())
                } else {
                    Toast.makeText(this@MainActivity, "No layout found", Toast.LENGTH_SHORT).show()
                }
            }
        })

        settingsScroll.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        settingsScroll.addView(Button(this).apply {
            text = "Close Settings"
            setPadding(16, 12, 16, 12)
            setOnClickListener { setContentView(buildMainScreen()) }
        })

        container.addView(settingsScroll)
        setContentView(container)
    }

    private fun showLayoutEditor(previousView: View? = null) {
        val container = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val editorView = ControlOverlayView(this).apply {
            editMode = true
            elements = ControlsRepository.load(this@MainActivity)
            onLayoutChanged = { ControlsRepository.save(this@MainActivity, elements) }
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        container.addView(editorView)

        val doneButton = Button(this).apply {
            text = "Done"
            setOnClickListener {
                controlView.elements = ControlsRepository.load(this@MainActivity)
                controlView.invalidate()
                setContentView(buildMainScreen())
            }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(24, 24, 24, 0)
            }
        }
        container.addView(doneButton)
        setContentView(container)

    private fun startHIDService() {
        startForegroundService(Intent(this, BluetoothHIDService::class.java))
        hidServiceRunning = true
        updateBluetoothStatus()
    }

    private fun updateBluetoothStatus() {
        val btEnabled = bluetoothAdapter?.isEnabled ?: false
        val status = when {
            hidServiceRunning && btEnabled -> "BT Gamepad: Ready"
            hidServiceRunning -> "BT Gamepad: Pairing..."
            else -> "BT Gamepad: Off"
        }
        statusView.text = status
    }

    private fun performExport() {
        val path = ControlsRepository.exportLayout(this)
        if (path == null) {
            Toast.makeText(this, "Export failed. Check storage permission.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Layout exported to $path", Toast.LENGTH_LONG).show()
        }
    }
}
