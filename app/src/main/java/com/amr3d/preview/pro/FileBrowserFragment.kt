package com.amr3d.preview.pro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File

class FileBrowserFragment : Fragment() {

    private lateinit var listView: ListView
    private lateinit var currentPathText: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var searchBox: EditText
    private lateinit var formatBadge: TextView
    private lateinit var btnToggleFormatFilter: Button
    private lateinit var formatFilterPanel: LinearLayout
    private lateinit var filterMatchCount: TextView
    private lateinit var cbFilterStl: CheckBox
    private lateinit var cbFilterObj: CheckBox
    private lateinit var cbFilterGlb: CheckBox
    private lateinit var cbFilterDxf: CheckBox
    private lateinit var cbFilterAi: CheckBox
    private lateinit var cbFilterPdf: CheckBox

    private val pathStack = ArrayDeque<File>()
    private var currentPath = Environment.getExternalStorageDirectory()
    private val supportedExtensions = setOf("stl", "dxf", "obj", "glb", "ai", "pdf")
    private var loadJob: Job? = null
    /** كل عناصر المجلد الحالي (قبل أي فلترة بحث/صيغة) — محتاجينها عشان الفلترة
     * تشتغل من غير إعادة تحميل من القرص في كل مرة */
    private var currentEntries: List<File> = emptyList()
    private var filterPanelOpen = false

    interface OnFileSelectedListener { fun onFileSelected(file: File) }
    var fileSelectedListener: OnFileSelectedListener? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_file_browser, container, false)
        AppTheme.applyThemeRecursively(view, requireContext())
        listView        = view.findViewById(R.id.fileList)
        currentPathText = view.findViewById(R.id.currentPath)
        btnBack         = view.findViewById(R.id.btnBackDir)
        progressBar     = view.findViewById(R.id.browserProgress)
        searchBox       = view.findViewById(R.id.searchBox)
        formatBadge     = view.findViewById(R.id.formatBadge)
        btnToggleFormatFilter = view.findViewById(R.id.btnToggleFormatFilter)
        formatFilterPanel     = view.findViewById(R.id.formatFilterPanel)
        filterMatchCount = view.findViewById(R.id.filterMatchCount)
        cbFilterStl = view.findViewById(R.id.cbFilterStl)
        cbFilterObj = view.findViewById(R.id.cbFilterObj)
        cbFilterGlb = view.findViewById(R.id.cbFilterGlb)
        cbFilterDxf = view.findViewById(R.id.cbFilterDxf)
        cbFilterAi  = view.findViewById(R.id.cbFilterAi)
        cbFilterPdf = view.findViewById(R.id.cbFilterPdf)

        loadFilterPrefs()
        updateFormatBadge()

        btnBack.setOnClickListener { navigateUp() }

        // زرار الطي/الإظهار — نفس سلوك btnToggleToolbars في العارض الأساسي بالظبط
        btnToggleFormatFilter.setOnClickListener {
            filterPanelOpen = !filterPanelOpen
            formatFilterPanel.visibility = if (filterPanelOpen) View.VISIBLE else View.GONE
            btnToggleFormatFilter.animate().rotation(if (filterPanelOpen) 180f else 0f).setDuration(200).start()
        }

        val filterListener = { _: android.widget.CompoundButton, _: Boolean ->
            saveFilterPrefs()
            updateFormatBadge()
            applyFilters()
        }
        cbFilterStl.setOnCheckedChangeListener(filterListener)
        cbFilterObj.setOnCheckedChangeListener(filterListener)
        cbFilterGlb.setOnCheckedChangeListener(filterListener)
        cbFilterDxf.setOnCheckedChangeListener(filterListener)
        cbFilterAi.setOnCheckedChangeListener(filterListener)
        cbFilterPdf.setOnCheckedChangeListener(filterListener)

        view.findViewById<TextView>(R.id.btnFilterSelectAll).setOnClickListener {
            listOf(cbFilterStl, cbFilterObj, cbFilterGlb, cbFilterDxf, cbFilterAi, cbFilterPdf).forEach { it.isChecked = true }
        }
        view.findViewById<TextView>(R.id.btnFilterClearAll).setOnClickListener {
            listOf(cbFilterStl, cbFilterObj, cbFilterGlb, cbFilterDxf, cbFilterAi, cbFilterPdf).forEach { it.isChecked = false }
        }

        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                applyFilters()
            }
        })
        checkPermissionAndLoad()
        return view
    }

    /** الصيغ المفعّلة حاليًا حسب حالة التشيك بوكسات */
    private fun activeExtensions(): Set<String> {
        val set = HashSet<String>()
        if (cbFilterStl.isChecked) set.add("stl")
        if (cbFilterObj.isChecked) set.add("obj")
        if (cbFilterGlb.isChecked) set.add("glb")
        if (cbFilterDxf.isChecked) set.add("dxf")
        if (cbFilterAi.isChecked) set.add("ai")
        if (cbFilterPdf.isChecked) set.add("pdf")
        return set
    }

    private fun filterPrefs() = requireContext().getSharedPreferences("amr3d_prefs", 0)

    /** حفظ اختيار الفلترة عشان يفضل زي ما هو المرة الجاية — مش محتاج تعيد ضبطه كل مرة */
    private fun saveFilterPrefs() {
        filterPrefs().edit()
            .putStringSet("file_filter_extensions", activeExtensions())
            .apply()
    }

    private fun loadFilterPrefs() {
        val saved = filterPrefs().getStringSet("file_filter_extensions", null) ?: supportedExtensions
        cbFilterStl.isChecked = "stl" in saved
        cbFilterObj.isChecked = "obj" in saved
        cbFilterGlb.isChecked = "glb" in saved
        cbFilterDxf.isChecked = "dxf" in saved
        cbFilterAi.isChecked  = "ai"  in saved
        cbFilterPdf.isChecked = "pdf" in saved
    }

    /** بادچ صغير أعلى الشاشة بيعرض ملخص الصيغ المفعّلة حاليًا */
    private fun updateFormatBadge() {
        val active = activeExtensions()
        formatBadge.text = when {
            active.size == supportedExtensions.size -> "🧊📐 الكل"
            active.isEmpty() -> "⚠️ لا شيء"
            else -> active.sorted().joinToString(" · ") { it.uppercase() }
        }
    }

    /** بيفلتر القائمة المعروضة حسب الصيغ المفعّلة + نص البحث مع بعض، من غير أي قراءة جديدة من القرص */
    private fun applyFilters() {
        if (!isAdded) return
        val query = searchBox.text?.toString().orEmpty()
        val active = activeExtensions()
        val filtered = currentEntries.filter { f ->
            val extOk = f.isDirectory || f.extension.lowercase() in active
            val queryOk = query.isBlank() || f.name.contains(query, ignoreCase = true)
            extOk && queryOk
        }
        filterMatchCount.text = getString(R.string.files_filter_match_count, filtered.count { it.isFile })
        listView.adapter = FileRowAdapter(requireContext(), filtered)
        listView.setOnItemClickListener { _, _, pos, _ ->
            val file = filtered[pos]
            if (file.isDirectory) {
                pathStack.addLast(currentPath)
                currentPath = file
                updateBackButton()
                searchBox.setText("")
                loadDirectory(file)
            } else {
                fileSelectedListener?.onFileSelected(file)
            }
        }
    }

    private fun checkPermissionAndLoad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (android.os.Environment.isExternalStorageManager()) {
                loadDirectory(currentPath)
            } else {
                showAllFilesAccessRequest()
            }
        } else {
            val perm = Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(requireContext(), perm)
                == PackageManager.PERMISSION_GRANTED) {
                loadDirectory(currentPath)
            } else {
                @Suppress("DEPRECATION")
                requestPermissions(arrayOf(perm), 100)
            }
        }
    }

    private fun showAllFilesAccessRequest() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_permission_title))
            .setMessage(getString(R.string.dialog_permission_message))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.dialog_open_settings)) { _, _ ->
                try {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:${requireContext().packageName}")
                    )
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        startActivity(android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                        ))
                    } catch (_: Exception) {
                        Toast.makeText(context, getString(R.string.toast_settings_open_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.dialog_later)) { _, _ ->
                Toast.makeText(context, getString(R.string.toast_some_folders_hidden), Toast.LENGTH_LONG).show()
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            loadDirectory(currentPath)
        else
            Toast.makeText(context, getString(R.string.toast_file_access_required), Toast.LENGTH_LONG).show()
    }

    private fun navigateUp() {
        if (pathStack.isNotEmpty()) {
            currentPath = pathStack.removeLast()
            loadDirectory(currentPath)
        } else {
            Toast.makeText(context, getString(R.string.toast_root_folder), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateBackButton() {
        if (!isAdded) return
        btnBack.alpha = if (pathStack.isEmpty()) 0.4f else 1.0f
    }

    // ══ تحميل المجلد بشكل async لتجنب تجميد الـ UI ══
    private fun loadDirectory(dir: File) {
        if (!isAdded) return
        loadJob?.cancel()
        progressBar.visibility = View.VISIBLE
        listView.visibility = View.GONE
        currentPathText.text = dir.absolutePath

        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val allFiles = try { dir.listFiles() } catch (_: Exception) { null }
                        ?: return@withContext null

                    // المجلدات — بدون بحث عميق لتجنب التأخير
                    val dirs = allFiles
                        .filter { it.isDirectory && !it.isHidden && it.canRead() }
                        .sortedBy { it.name.lowercase() }

                    // الملفات المدعومة فقط
                    val files = allFiles
                        .filter { it.isFile && it.extension.lowercase() in supportedExtensions }
                        .sortedBy { it.name.lowercase() }

                    Pair(dirs, files)
                }

                if (!isAdded) return@launch
                progressBar.visibility = View.GONE
                listView.visibility = View.VISIBLE

                if (result == null) {
                    Toast.makeText(context, getString(R.string.toast_cannot_access_folder), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val (dirs, files) = result
                val entries = dirs + files
                currentEntries = entries

                if (entries.isEmpty()) {
                    Toast.makeText(context, getString(R.string.toast_no_stl_dxf_here), Toast.LENGTH_SHORT).show()
                }

                if (searchBox.text.isNotEmpty()) searchBox.setText("")
                applyFilters() // بيطبّق فلتر الصيغ الحالي + يظبط الـ adapter وclick listener

                updateBackButton()

            } catch (_: CancellationException) {
                // تم إلغاء الـ job — طبيعي
            } catch (e: Exception) {
                if (!isAdded) return@launch
                progressBar.visibility = View.GONE
                Toast.makeText(context, getString(R.string.toast_error_prefix, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // عند العودة من إعدادات النظام، تحقق من الإذن وحمّل تلقائياً
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            android.os.Environment.isExternalStorageManager() &&
            listView.adapter == null) {
            loadDirectory(currentPath)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loadJob?.cancel()
    }
}

/**
 * Adapter مخصّص لصفوف الملفات/المجلدات — بديل الـ ArrayAdapter الأساسي القديم اللي كان
 * بيعرض نص عادي بس. هنا كل صف بيتلوّن حسب نوعه (مجلد/STL/DXF) زي معاينة الـ HTML.
 */
class FileRowAdapter(
    private val ctx: android.content.Context,
    private val items: List<File>
) : BaseAdapter() {

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): File = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(ctx)
            .inflate(R.layout.item_file_row, parent, false)

        val file = items[position]
        val icon = view.findViewById<TextView>(R.id.rowIcon)
        val name = view.findViewById<TextView>(R.id.rowName)
        val meta = view.findViewById<TextView>(R.id.rowMeta)

        name.text = file.name

        if (file.isDirectory) {
            icon.text = "📁"
            icon.setBackgroundResource(R.drawable.bg_file_icon_folder)
            val childCount = try { file.listFiles()?.size ?: 0 } catch (_: Exception) { 0 }
            meta.text = ctx.getString(R.string.files_folder_item_count, childCount)
        } else {
            val ext = file.extension.uppercase()
            val kb = file.length() / 1024
            val size = if (kb >= 1024) "${"%.1f".format(kb / 1024f)} MB" else "$kb KB"
            if (ext == "STL" || ext == "OBJ" || ext == "GLB") {
                icon.text = "🧊"
                icon.setBackgroundResource(R.drawable.bg_file_icon_stl)
            } else {
                // DXF و AI و PDF كلهم بيترسموا على نفس عارض الـ 2D بالظبط
                icon.text = "📐"
                icon.setBackgroundResource(R.drawable.bg_file_icon_dxf)
            }
            meta.text = ctx.getString(R.string.files_item_meta_format, ext, size)
        }

        return view
    }
}
