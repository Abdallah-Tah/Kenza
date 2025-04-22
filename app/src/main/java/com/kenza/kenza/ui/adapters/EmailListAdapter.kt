package com.kenza.kenza.ui.adapters

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kenza.kenza.database.models.CleanedEmail // Or a different Email model if needed

class EmailListAdapter(private val emails: List<CleanedEmail>) : RecyclerView.Adapter<EmailListAdapter.EmailViewHolder>() {

    class EmailViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // TODO: Bind email data to view elements (e.g., TextViews)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmailViewHolder {
        // TODO: Inflate layout for email item
        TODO("Provide layout inflation")
    }

    override fun onBindViewHolder(holder: EmailViewHolder, position: Int) {
        val email = emails[position]
        // TODO: Bind data in holder.bind(email)
    }

    override fun getItemCount() = emails.size
}