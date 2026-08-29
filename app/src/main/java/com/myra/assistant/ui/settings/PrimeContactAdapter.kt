package com.myra.assistant.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.myra.assistant.R
import com.myra.assistant.model.PrimeContact

class PrimeContactAdapter(
    private val onDeleteClicked: (Int) -> Unit
) : RecyclerView.Adapter<PrimeContactAdapter.ViewHolder>() {

    private val contacts = mutableListOf<PrimeContact>()

    fun setContacts(newContacts: List<PrimeContact>) {
        contacts.clear()
        contacts.addAll(newContacts)
        notifyDataSetChanged()
    }

    fun getContacts(): List<PrimeContact> = contacts.toList()

    fun addContact(contact: PrimeContact) {
        contacts.add(contact)
        notifyItemInserted(contacts.size - 1)
    }

    fun removeContact(position: Int) {
        if (position in contacts.indices) {
            contacts.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_prime_contact, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = contacts[position]
        holder.bind(contact) {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onDeleteClicked(pos)
            }
        }
    }

    override fun getItemCount(): Int = contacts.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.primeItemName)
        private val numberText: TextView = itemView.findViewById(R.id.primeItemNumber)
        private val deleteBtn: ImageButton = itemView.findViewById(R.id.primeItemDelete)

        fun bind(contact: PrimeContact, onDelete: () -> Unit) {
            nameText.text = contact.name
            numberText.text = contact.number
            deleteBtn.setOnClickListener { onDelete() }
        }
    }
}
