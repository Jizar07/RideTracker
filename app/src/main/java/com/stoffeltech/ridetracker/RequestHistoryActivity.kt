package com.stoffeltech.ridetracker

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.stoffeltech.ridetracker.adapters.RequestHistoryAdapter
import com.stoffeltech.ridetracker.services.HistoryManager

class RequestHistoryActivity : AppCompatActivity() {

    private lateinit var rvHistory: RecyclerView
    private lateinit var historyAdapter: RequestHistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_request_history)

        supportActionBar?.title = "Request History"

        // Reference the RecyclerView from the layout
        rvHistory = findViewById(R.id.rvHistory)
        historyAdapter = RequestHistoryAdapter(listOf())
        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = historyAdapter

        val btnClearHistory = findViewById<Button>(R.id.btnClearHistory)
        btnClearHistory.setOnClickListener {
            // pass 'this' (or 'applicationContext')
            HistoryManager.clearHistory(this)
            historyAdapter.updateData(listOf())
        }


        // Load current data from HistoryManager
        val allHistory = com.stoffeltech.ridetracker.services.HistoryManager.getAllHistory()
        historyAdapter.updateData(allHistory)
    }
}

