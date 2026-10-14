package app.foldboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView

/** Emoji picker with recents, shown in place of the keys. */
@SuppressLint("ViewConstructor")
class EmojiPanel(
    context: Context,
    private val prefs: Prefs,
    private val onEmoji: (String) -> Unit,
    private val onAbc: () -> Unit,
    private val onBackspace: () -> Unit,
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private var theme: KbTheme = KbTheme.dark(0xFF4BA3F0.toInt())
    private var category = 0
    private val grid = GridView(context)
    private val tabs = LinearLayout(context)
    private val adapter = EmojiAdapter()

    init {
        orientation = VERTICAL
        grid.numColumns = GridView.AUTO_FIT
        grid.columnWidth = (50 * density).toInt()
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ ->
            val e = adapter.getItem(pos)
            remember(e)
            onEmoji(e)
        }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        tabs.orientation = HORIZONTAL
        tabs.gravity = Gravity.CENTER_VERTICAL
        addView(tabs, LayoutParams(LayoutParams.MATCH_PARENT, (46 * density).toInt()))
        buildTabs()
        category = if (recents().isEmpty()) 1 else 0
    }

    fun applyTheme(t: KbTheme) {
        theme = t
        setBackgroundColor(t.background)
        tabs.setBackgroundColor(t.strip)
        buildTabs()
        adapter.notifyDataSetChanged()
    }

    private fun buildTabs() {
        tabs.removeAllViews()
        tabs.addView(textButton("ABC") { onAbc() }, LayoutParams((64 * density).toInt(), LayoutParams.MATCH_PARENT))
        for ((i, c) in CATEGORIES.withIndex()) {
            val b = textButton(c.first) { category = i; adapter.notifyDataSetChanged(); grid.setSelection(0); buildTabs() }
            b.textSize = 20f
            if (i == category) b.setTextColor(theme.accent)
            tabs.addView(b, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        tabs.addView(BackspaceButton(context).apply { setOnClickListener { onBackspace() } }, LayoutParams((64 * density).toInt(), LayoutParams.MATCH_PARENT))
    }

    private fun textButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(theme.text)
        typeface = Typeface.DEFAULT_BOLD
        textSize = 15f
        setOnClickListener { onClick() }
    }

    private inner class BackspaceButton(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            p.color = theme.text
            Icons.backspace(canvas, width / 2f, height / 2f, 26 * density, p)
        }
    }

    private fun recents(): List<String> =
        prefs.sp.getString(RECENTS, "")!!.split(' ').filter { it.isNotEmpty() }

    private fun remember(e: String) {
        val list = (listOf(e) + recents().filter { it != e }).take(32)
        prefs.sp.edit().putString(RECENTS, list.joinToString(" ")).apply()
    }

    private fun current(): List<String> =
        if (category == 0) recents() else CATEGORIES[category].second.split(' ').filter { it.isNotEmpty() }

    private inner class EmojiAdapter : BaseAdapter() {
        override fun getCount() = current().size
        override fun getItem(position: Int) = current()[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 27f
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * density).toInt())
            }
            tv.text = getItem(position)
            tv.setTextColor(theme.text)
            return tv
        }
    }

    companion object {
        private const val RECENTS = "emoji_recent"
        val CATEGORIES: List<Pair<String, String>> = listOf(
            "🕘" to "",
            "😀" to "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 🥲 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😮‍💨 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 🥴 😵 🤯 🤠 🥳 🥸 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 🥹 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 💩 🤡 👻 👽 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾",
            "👍" to "👍 👎 👌 🤌 🤏 ✌️ 🤞 🫰 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐️ 🖖 👋 🫡 👏 🙌 🫶 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦾 🧠 👀 👁️ 👅 👄 🫦 👶 🧒 👦 👧 🧑 👱 👨 🧔 👩 🧓 👴 👵 🙍 🙎 🙅 🙆 💁 🙋 🧏 🙇 🤦 🤷 👮 🕵️ 💂 👷 🤴 👸 👳 🤵 👰 🤰 🎅 🦸 🦹 🧙 🧚 🧛 🧜 🧝 💆 💇 🚶 🏃 💃 🕺 👯 🧘 🛀 👫 👬 👭 💏 💑 👪",
            "❤️" to "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❤️‍🔥 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ☮️ ✝️ ☯️ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ ✅ ☑️ ✔️ ❌ ❎ ➕ ➖ ➗ ✖️ 💯 💢 💥 💫 💦 💨 🕳️ 💬 🗨️ 💭 💤 🔥 ✨ 🌟 ⭐ ⚡ ⚠️ 🚫 ⛔ 📛 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤 🔺 🔻 🔸 🔹 ▶️ ⏸️ ⏹️ ⏺️ ⏭️ ⏮️ 🔁 🔂 🔀 🆗 🆕 🆒 🆓 🆙 🔝 🔜 ‼️ ⁉️ ❓ ❔ ❗ ❕ ©️ ®️ ™️",
            "🐶" to "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐻‍❄️ 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🪱 🐛 🦋 🐌 🐞 🐜 🐢 🐍 🦎 🐙 🦑 🦐 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🦍 🐘 🦛 🦏 🐪 🦒 🦘 🐃 🐄 🐎 🐖 🐏 🐑 🐐 🦌 🐕 🐩 🐈 🐓 🦃 🦚 🦜 🦢 🕊️ 🐇 🦝 🦨 🦡 🦦 🦥 🐁 🐿️ 🦔 🌵 🎄 🌲 🌳 🌴 🌱 🌿 ☘️ 🍀 🍁 🍂 🍃 🌷 🌹 🥀 🌺 🌸 🌼 🌻 🌞 🌝 🌚 🌙 🌎 🪐 ☀️ ⛅ ☁️ 🌧️ ⛈️ 🌩️ ❄️ ☃️ ⛄ 🌈 🌊",
            "🍕" to "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🫑 🌽 🥕 🧄 🧅 🥔 🍠 🥐 🥯 🍞 🥖 🥨 🧀 🥚 🍳 🧈 🥞 🧇 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🥙 🧆 🌮 🌯 🥗 🥘 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🍤 🍙 🍚 🍘 🍥 🥠 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🌰 🥜 🍯 🥛 ☕ 🍵 🧃 🥤 🧋 🍶 🍺 🍻 🥂 🍷 🥃 🍸 🍹 🧉 🍾 🧊",
            "⚽" to "⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🏓 🏸 🏒 🏑 🥍 🏏 🥅 ⛳ 🏹 🎣 🤿 🥊 🥋 🎽 🛹 🛼 ⛸️ 🎿 🏂 🏋️ 🤸 ⛹️ 🤺 🤾 🏌️ 🏇 🧘 🏄 🏊 🤽 🚣 🧗 🚵 🚴 🏆 🥇 🥈 🥉 🏅 🎖️ 🎗️ 🎫 🎟️ 🎪 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🪕 🎻 🎲 ♟️ 🎯 🎳 🎮 🎰 🧩 🚗 🚕 🚙 🚌 🏎️ 🚓 🚑 🚒 🚐 🛻 🚚 🚜 🛵 🏍️ 🚲 🛴 🚨 🚆 🚇 ✈️ 🛫 🛬 🚀 🛸 🚁 ⛵ 🚤 🛳️ ⚓ 🗺️ 🏠 🏡 🏢 🏥 🏦 🏨 🏫 ⛪ 🕌 🗽 🗼 🏰 🎡 🎢 🏖️ 🏝️ 🏔️ ⛺",
            "💡" to "⌚ 📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 💽 💾 💿 📷 📸 📹 🎥 📞 ☎️ 📺 📻 🎙️ ⏰ ⏳ 🔋 🔌 💡 🔦 🕯️ 💸 💵 💴 💶 💷 💰 💳 💎 ⚖️ 🔧 🔨 🛠️ ⛏️ 🔩 ⚙️ 🧲 🔫 💣 🔪 🛡️ 🔮 🧿 💈 🔭 🔬 💊 💉 🩹 🧬 🦠 🧪 🌡️ 🧹 🧺 🧻 🚽 🛁 🧼 🔑 🗝️ 🚪 🛋️ 🛏️ 🧸 🖼️ 🛍️ 🛒 🎁 🎈 🎏 🎀 🎊 🎉 ✉️ 📩 📨 📧 📦 📫 📮 📜 📃 📄 📑 📊 📈 📉 🗒️ 📆 📅 🗑️ 📇 🗃️ 🗳️ 🗄️ 📋 📁 📂 📰 📓 📔 📒 📕 📗 📘 📙 📚 📖 🔖 🔗 📎 📐 📏 📌 📍 ✂️ 🖊️ 🖋️ ✒️ 🖌️ 🖍️ 📝 ✏️ 🔍 🔎 🔏 🔐 🔒 🔓",
            "🏁" to "🏁 🚩 🎌 🏴 🏳️ 🏳️‍🌈 🏴‍☠️ 🇺🇸 🇨🇦 🇬🇧 🇮🇳 🇦🇺 🇩🇪 🇫🇷 🇪🇸 🇮🇹 🇵🇹 🇧🇷 🇲🇽 🇯🇵 🇰🇷 🇨🇳 🇳🇱 🇧🇪 🇨🇭 🇸🇪 🇳🇴 🇩🇰 🇫🇮 🇮🇪 🇵🇱 🇺🇦 🇹🇷 🇬🇷 🇮🇱 🇦🇪 🇸🇦 🇪🇬 🇿🇦 🇳🇬 🇰🇪 🇦🇷 🇨🇴 🇨🇱 🇵🇪 🇵🇭 🇮🇩 🇹🇭 🇻🇳 🇸🇬 🇲🇾 🇳🇿 🇵🇰 🇧🇩 🇱🇰 🇳🇵",
        )
    }
}
