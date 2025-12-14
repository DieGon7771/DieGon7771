package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast

class Settings(
    private val plugin: ArtePlugin,
    private val sharedPref: SharedPreferences,
) : BottomSheetDialogFragment() {
    private var currentLanguage: String? = null
    private var currentLanguageIndex: Int = sharedPref.getInt("languageIndex", 0)
    private val languages = arrayOf(
        "\uD83C\uDDEE\uD83C\uDDF9 Italian",
        "\uD83C\uDDEB\uD83C\uDDF7 Français",
        "\uD83C\uDDE9\uD83C\uDDEA Deutsch",
        "\uD83C\uDDEC\uD83C\uDDE7 English",
        "\uD83C\uDDEA\uD83C\uDDF8 Español",
        "\uD83C\uDDF5\uD83C\uDDF1 Polski",
        "\uD83C\uDDF7\uD83C\uDDF4 Română",
    )
    private fun View.makeTvCompatible() {
        this.setPadding(
            this.paddingLeft + 10,
            this.paddingTop + 10,
            this.paddingRight + 10,
            this.paddingBottom + 10
        )
        this.background = getDrawable("outline")
    }

    // Helper function to get a drawable resource by name
    @SuppressLint("DiscouragedApi")
    @Suppress("SameParameterValue")
    private fun getDrawable(name: String): Drawable? {
        val id = plugin.resources?.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { ResourcesCompat.getDrawable(plugin.resources ?: return null, it, null) }
    }

    // Helper function to get a string resource by name
    @SuppressLint("DiscouragedApi")
    @Suppress("SameParameterValue")
    private fun getString(name: String): String? {
        val id = plugin.resources?.getIdentifier(name, "string", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { plugin.resources?.getString(it) }
    }

    // Generic findView function to find views by name
    @SuppressLint("DiscouragedApi")
    private fun <T : View> View.findViewByName(name: String): T? {
        val id = plugin.resources?.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return findViewById(id ?: return null)
    }

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val layoutId =
            plugin.resources?.getIdentifier("settings", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return layoutId?.let {
            inflater.inflate(plugin.resources?.getLayout(it), container, false)
        }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize views
        val headerTw: TextView? = view.findViewByName("header_tw")
        headerTw?.text = getString("header_tw")

        val domainTw: TextView? = view.findViewByName("lang_tw")
        domainTw?.text = getString("lang_tw")

        val languageDropdown: Spinner? = view.findViewByName("lang_dropdown")
        languageDropdown?.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, languages
        )
        languageDropdown?.setSelection(currentLanguageIndex)

        languageDropdown?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                currentLanguage = languages[position]
            }

            override fun onNothingSelected(p0: AdapterView<*>?) {
            }
        }


        val saveBtn: ImageButton? = view.findViewByName("save_btn")
        saveBtn?.makeTvCompatible()
        saveBtn?.setImageDrawable(getDrawable("save_icon"))

        saveBtn?.setOnClickListener {
            with(sharedPref.edit()) {
                this.clear()
                this.putInt("languageIndex", languages.indexOf(currentLanguage))
                this.putString("language", currentLanguage)
                this.apply()
            }
            showToast("Saved. Restart the app to apply the settings")
            dismiss()
        }


    }
}
