package com.vetramonitor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels

/**
 * Dialog for entering the Canon CCAPI base URL.
 * Example: http://192.168.1.2:8080
 */
class ConnectFragment : DialogFragment() {

    private val vm: MonitorViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_connect, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val etUrl    = view.findViewById<EditText>(R.id.etCcapiUrl)
        val btnOk    = view.findViewById<Button>(R.id.btnConnectOk)
        val btnClear = view.findViewById<Button>(R.id.btnDisconnect)

        etUrl.setText(vm.state.value.ccapiUrl)

        btnOk.setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(requireContext(), "Enter a URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            vm.connectCcapi(url)
            dismiss()
        }

        btnClear.setOnClickListener {
            vm.disconnectCcapi()
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.55).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }
}
