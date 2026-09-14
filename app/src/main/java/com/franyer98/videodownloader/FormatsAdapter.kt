package com.franyer98.videodownloader

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.franyer98.videodownloader.databinding.ItemFormatBinding

class FormatsAdapter(
    private val formats: List<VideoFormat>,
    private val onDownloadClick: (VideoFormat) -> Unit
) : RecyclerView.Adapter<FormatsAdapter.FormatViewHolder>() {

    inner class FormatViewHolder(val binding: ItemFormatBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FormatViewHolder {
        val binding = ItemFormatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FormatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FormatViewHolder, position: Int) {
        val format = formats[position]
        holder.binding.txtFormatLabel.text = format.label
        holder.binding.btnDownload.setOnClickListener { onDownloadClick(format) }
    }

    override fun getItemCount(): Int = formats.size
}
