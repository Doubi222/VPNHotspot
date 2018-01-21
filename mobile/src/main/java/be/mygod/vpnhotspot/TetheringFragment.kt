package be.mygod.vpnhotspot

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.databinding.BaseObservable
import android.databinding.DataBindingUtil
import android.os.Bundle
import android.os.IBinder
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.support.v7.util.SortedList
import android.support.v7.widget.DefaultItemAnimator
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.Toolbar
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import be.mygod.vpnhotspot.databinding.FragmentTetheringBinding
import be.mygod.vpnhotspot.databinding.ListitemInterfaceBinding
import be.mygod.vpnhotspot.net.NetUtils
import be.mygod.vpnhotspot.net.TetherType

class TetheringFragment : Fragment(), ServiceConnection, Toolbar.OnMenuItemClickListener {
    private abstract class BaseSorter<T> : SortedList.Callback<T>() {
        override fun onInserted(position: Int, count: Int) { }
        override fun areContentsTheSame(oldItem: T?, newItem: T?): Boolean = oldItem == newItem
        override fun onMoved(fromPosition: Int, toPosition: Int) { }
        override fun onChanged(position: Int, count: Int) { }
        override fun onRemoved(position: Int, count: Int) { }
        override fun areItemsTheSame(item1: T?, item2: T?): Boolean = item1 == item2
        override fun compare(o1: T?, o2: T?): Int =
                if (o1 == null) if (o2 == null) 0 else 1 else if (o2 == null) -1 else compareNonNull(o1, o2)
        abstract fun compareNonNull(o1: T, o2: T): Int
    }
    private open class DefaultSorter<T : Comparable<T>> : BaseSorter<T>() {
        override fun compareNonNull(o1: T, o2: T): Int = o1.compareTo(o2)
    }
    private object StringSorter : DefaultSorter<String>()

    inner class Data(val iface: String) : BaseObservable() {
        val icon: Int get() = TetherType.ofInterface(iface).icon
        var active = binder?.active?.contains(iface) == true
    }

    class InterfaceViewHolder(val binding: ListitemInterfaceBinding) : RecyclerView.ViewHolder(binding.root),
            View.OnClickListener {
        init {
            itemView.setOnClickListener(this)
        }

        override fun onClick(view: View) {
            val context = itemView.context
            val data = binding.data!!
            if (data.active) context.startService(Intent(context, TetheringService::class.java)
                    .putExtra(TetheringService.EXTRA_REMOVE_INTERFACE, data.iface))
            else ContextCompat.startForegroundService(context, Intent(context, TetheringService::class.java)
                    .putExtra(TetheringService.EXTRA_ADD_INTERFACE, data.iface))
            data.active = !data.active
        }
    }
    inner class InterfaceAdapter : RecyclerView.Adapter<InterfaceViewHolder>() {
        private val tethered = SortedList(String::class.java, StringSorter)

        fun update(data: Set<String>) {
            val oldEmpty = tethered.size() == 0
            tethered.clear()
            tethered.addAll(data)
            notifyDataSetChanged()
            if (oldEmpty != data.isEmpty())
                if (oldEmpty) crossFade(binding.empty, binding.interfaces)
                else crossFade(binding.interfaces, binding.empty)
        }

        override fun getItemCount() = tethered.size()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = InterfaceViewHolder(
                ListitemInterfaceBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: InterfaceViewHolder, position: Int) {
            holder.binding.data = Data(tethered[position])
        }
    }

    private lateinit var binding: FragmentTetheringBinding
    private var binder: TetheringService.TetheringBinder? = null
    val adapter = InterfaceAdapter()
    private val receiver = broadcastReceiver { _, intent ->
        adapter.update(NetUtils.getTetheredIfaces(intent.extras).toSet())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_tethering, container, false)
        binding.toolbar.inflateMenu(R.menu.tethering)
        binding.toolbar.setOnMenuItemClickListener(this)
        binding.interfaces.layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        val animator = DefaultItemAnimator()
        animator.supportsChangeAnimations = false   // prevent fading-in/out when rebinding
        binding.interfaces.itemAnimator = animator
        binding.interfaces.adapter = adapter
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        val context = context!!
        context.registerReceiver(receiver, intentFilter(NetUtils.ACTION_TETHER_STATE_CHANGED))
        context.bindService(Intent(context, TetheringService::class.java), this, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        val context = context!!
        context.unbindService(this)
        context.unregisterReceiver(receiver)
        super.onStop()
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val binder = service as TetheringService.TetheringBinder
        this.binder = binder
        binder.fragment = this
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        binder?.fragment = null
        binder = null
    }

    override fun onMenuItemClick(item: MenuItem) = when (item.itemId) {
        R.id.systemTethering -> {
            startActivity(Intent().setClassName("com.android.settings",
                    "com.android.settings.Settings\$TetherSettingsActivity"))
            true
        }
        else -> false
    }

    private fun crossFade(old: View, new: View) {
        val shortAnimTime = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
        old.animate().alpha(0F).setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                old.visibility = View.GONE
            }
        }).duration = shortAnimTime
        new.alpha = 0F
        new.visibility = View.VISIBLE
        new.animate().alpha(1F).setListener(null).duration = shortAnimTime
    }
}
