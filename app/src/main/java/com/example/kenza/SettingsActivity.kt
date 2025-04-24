package com.example.kenza

import android.app.TimePickerDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.work.*
import com.example.kenza.workers.EmailCleaningWorker
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import com.example.kenza.utils.PreferencesManager


class SettingsActivity : AppCompatActivity() {

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var switchSchedule: SwitchMaterial
    private lateinit var tvSelectedTime: TextView
    private lateinit var btnPickTime: Button
    private lateinit var etAddExclusion: EditText
    private lateinit var btnAddExclusion: Button
    private lateinit var rvExclusions: RecyclerView
    private lateinit var exclusionAdapter: ExclusionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        preferencesManager = (application as MainApplication).preferencesManager

        // --- Initialize UI Elements ---
        switchSchedule = findViewById(R.id.switch_schedule_enable)
        tvSelectedTime = findViewById(R.id.tv_selected_schedule_time)
        btnPickTime = findViewById(R.id.btn_pick_schedule_time)
        etAddExclusion = findViewById(R.id.et_add_exclusion)
        btnAddExclusion = findViewById(R.id.btn_add_exclusion)
        rvExclusions = findViewById(R.id.rv_exclusions)

        setupRecyclerView()
        loadSettings()
        setupListeners()
    }

    private fun setupRecyclerView() {
        exclusionAdapter = ExclusionAdapter { senderToRemove ->
            preferencesManager.removeExclusionSender(senderToRemove)
            loadExclusionList()
        }
        rvExclusions.layoutManager = LinearLayoutManager(this)
        rvExclusions.adapter = exclusionAdapter
    }

    private fun loadSettings() {
        switchSchedule.isChecked = preferencesManager.isScheduleEnabled()

        val (hour, minute) = preferencesManager.getScheduleTime()
        updateSelectedTimeText(hour, minute)

        loadExclusionList()
    }

    private fun loadExclusionList() {
        val exclusions = preferencesManager.getExclusionSenders().toList().sorted()
        exclusionAdapter.submitList(exclusions)
    }

    private fun updateSelectedTimeText(hour: Int, minute: Int) {
        if (hour != -1 && minute != -1) {
            tvSelectedTime.text = String.format(Locale.getDefault(), "Scheduled for: %02d:%02d daily", hour, minute)
        } else {
            tvSelectedTime.text = "Schedule time not set"
        }
    }

    private fun setupListeners() {
        switchSchedule.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.setScheduleEnabled(isChecked)
            if (isChecked) {
                val (hour, minute) = preferencesManager.getScheduleTime()
                if (hour != -1) {
                    scheduleCleaningWorker(hour, minute)
                } else {
                    Toast.makeText(this, "Please set a schedule time first", Toast.LENGTH_SHORT).show()
                    switchSchedule.isChecked = false
                    preferencesManager.setScheduleEnabled(false)
                }
            } else {
                cancelCleaningWorker()
            }
        }

        btnPickTime.setOnClickListener {
            showTimePickerDialog()
        }

        btnAddExclusion.setOnClickListener {
            val sender = etAddExclusion.text.toString().trim()
            if (sender.isNotEmpty()) {
                preferencesManager.addExclusionSender(sender)
                etAddExclusion.text.clear()
                loadExclusionList()
                Toast.makeText(this, "Added exclusion: $sender", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enter a sender email or domain", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showTimePickerDialog() {
        val (currentHour, currentMinute) = preferencesManager.getScheduleTime()
        val calendar = Calendar.getInstance()
        val hourToUse = if (currentHour != -1) currentHour else calendar.get(Calendar.HOUR_OF_DAY)
        val minuteToUse = if (currentMinute != -1) currentMinute else calendar.get(Calendar.MINUTE)

        TimePickerDialog(this, { _, hourOfDay, minute ->
            preferencesManager.setScheduleTime(hourOfDay, minute)
            updateSelectedTimeText(hourOfDay, minute)
            if (preferencesManager.isScheduleEnabled()) {
                scheduleCleaningWorker(hourOfDay, minute)
            }
        }, hourToUse, minuteToUse, true).show()
    }

    private fun scheduleCleaningWorker(hour: Int, minute: Int) {
        val workManager = WorkManager.getInstance(applicationContext)

        val currentTime = Calendar.getInstance()
        val targetTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(currentTime)) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }
        val initialDelay = targetTime.timeInMillis - currentTime.timeInMillis

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicWorkRequest = PeriodicWorkRequestBuilder<EmailCleaningWorker>(
            repeatInterval = 1,
            repeatIntervalTimeUnit = TimeUnit.DAYS
        )
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .addTag(EmailCleaningWorker.WORK_NAME)
            .build()

        workManager.enqueueUniquePeriodicWork(
            EmailCleaningWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            periodicWorkRequest
        )
        Log.d("SettingsActivity", "Scheduled cleaning worker for $hour:$minute daily.")
        Toast.makeText(this, "Auto-cleaning scheduled daily for $hour:$minute", Toast.LENGTH_SHORT).show()
    }

    private fun cancelCleaningWorker() {
        val workManager = WorkManager.getInstance(applicationContext)
        workManager.cancelUniqueWork(EmailCleaningWorker.WORK_NAME)
        Log.d("SettingsActivity", "Cancelled scheduled cleaning worker.")
        Toast.makeText(this, "Auto-cleaning cancelled", Toast.LENGTH_SHORT).show()
    }
}

// --- ADD ExclusionAdapter Definition ---
class ExclusionAdapter(private val onDeleteClick: (String) -> Unit) :
    ListAdapter<String, ExclusionAdapter.ExclusionViewHolder>(ExclusionDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExclusionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_exclusion, parent, false)
        return ExclusionViewHolder(view, onDeleteClick)
    }

    override fun onBindViewHolder(holder: ExclusionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ExclusionViewHolder(
        itemView: View,
        private val onDeleteClick: (String) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val senderTextView: TextView = itemView.findViewById(R.id.tv_exclusion_sender)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.btn_delete_exclusion)
        private var currentSender: String? = null

        init {
            deleteButton.setOnClickListener {
                currentSender?.let { sender ->
                    onDeleteClick(sender)
                }
            }
        }

        fun bind(sender: String) {
            currentSender = sender
            senderTextView.text = sender
        }
    }
}

// --- ADD DiffUtil Callback ---
class ExclusionDiffCallback : DiffUtil.ItemCallback<String>() {
    override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
        return oldItem == newItem
    }

    override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
        return oldItem == newItem
    }
}