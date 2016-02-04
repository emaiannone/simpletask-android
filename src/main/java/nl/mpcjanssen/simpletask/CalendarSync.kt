/**

 * Copyright (c) 2015 Vojtech Kral

 * LICENSE:

 * Simpletask is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 2 of the License, or (at your option) any
 * later version.

 * Simpletask is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 * details.

 * You should have received a copy of the GNU General Public License along with Sinpletask.  If not, see
 * //www.gnu.org/licenses/>.

 * @author Vojtech Kral
 * *
 * @license http://www.gnu.org/licenses/gpl.html
 * *
 * @copyright 2015 Vojtech Kral
 */

package nl.mpcjanssen.simpletask

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.support.v4.content.ContextCompat
import hirondelle.date4j.DateTime
import nl.mpcjanssen.simpletask.task.TodoListItem
import nl.mpcjanssen.simpletask.util.*


import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit


class CalendarSync(private val m_app: TodoApplication, syncDues: Boolean, syncThresholds: Boolean) {
    private val log: Logger

    private inner class SyncRunnable : Runnable {
        override fun run() {
            try {
                this@CalendarSync.sync()
            } catch (e: Exception) {
                log.error(TAG, "STPE exception", e)
            }

        }
    }

    private val m_sync_runnable: SyncRunnable
    private val m_cr: ContentResolver
    private var m_sync_type: Int = 0
    private var m_rem_margin = 1440
    private var m_rem_time = DateTime.forTimeOnly(12, 0, 0, 0)
    private val m_stpe: ScheduledThreadPoolExecutor

    private fun calendarError(e: Exception?) {
        if (e != null) {
            log.error(TAG, "Error writing calendar", e)
        } else {
            log.error(TAG, "Error writing calendar")
        }
        m_sync_type = 0
        showToastShort(TodoApplication.appContext, R.string.calendar_write_error)
    }

    private val calID: Long
        get() {
            val projection = arrayOf(Calendars._ID, Calendars.NAME)
            val selection = Calendars.NAME + " = ?"
            val args = arrayOf(CAL_NAME)
            /* Check for calendar permission */
            val permissionCheck = ContextCompat.checkSelfPermission(m_app,
                    Manifest.permission.WRITE_CALENDAR)

            if (permissionCheck == PackageManager.PERMISSION_DENIED) {
                if (m_sync_type == 0) {
                    return -1
                } else {
                    throw IllegalStateException("no calendar access")
                }
            }

            val cursor = m_cr.query(CAL_URI, projection, selection, args, null) ?: throw IllegalArgumentException("null cursor")
            if (cursor.count == 0) return -1
            cursor.moveToFirst()
            val ret = cursor.getLong(0)
            cursor.close()
            return ret
        }

    private fun addCalendar() {
        val cv = ContentValues()
        cv.apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
            put(Calendars.NAME, CAL_NAME)
            put(Calendars.CALENDAR_DISPLAY_NAME, m_app.getString(R.string.calendar_disp_name))
            put(Calendars.CALENDAR_COLOR, CAL_COLOR)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
        }
        m_cr.insert(CAL_URI, cv)
    }

    private fun removeCalendar() {
        val selection = Calendars.NAME + " = ?"
        val args = arrayOf(CAL_NAME)
        try {
            val ret = m_cr.delete(CAL_URI, selection, args)
            if (ret == 1)
                log.debug(TAG, "Calendar removed")
            else
                log.error(TAG, "Unexpected return value while removing calendar: " + ret)
        } catch (e: Exception) {
            log.error(TAG, "Exception while removing calendar", e)
        }

    }

    @SuppressWarnings("NewApi")
    private fun insertEvt(calID: Long, date: DateTime, title: String, description: String) {
        val values = ContentValues()

        val localZone = Calendar.getInstance().timeZone
        val dtstart = date.getMilliseconds(UTC)

        // Event:
        values.apply {
            put(Events.CALENDAR_ID, calID)
            put(Events.TITLE, title)
            put(Events.DTSTART, dtstart)
            put(Events.DTEND, dtstart + EVT_DURATION_DAY)  // Needs to be set to DTSTART +24h, otherwise reminders don't work
            put(Events.ALL_DAY, 1)
            put(Events.DESCRIPTION, description)
            put(Events.EVENT_TIMEZONE, UTC.id)
            put(Events.STATUS, Events.STATUS_CONFIRMED)
            put(Events.HAS_ATTENDEE_DATA, true)      // If this is not set, Calendar app is confused about Event.STATUS
            put(Events.CUSTOM_APP_PACKAGE, PACKAGE)
            put(Events.CUSTOM_APP_URI, Uri.withAppendedPath(Simpletask.URI_SEARCH, title).toString())
        }
        val uri = m_cr.insert(Events.CONTENT_URI, values)

        // Reminder:
        // Only create reminder if it's in the future, otherwise it would go off immediately
        // NOTE: DateTime.minus()/plus() only accept values >=0 and <10000 (goddamnit date4j!), hence the division.
        var remDate = date.minus(0, 0, 0, m_rem_margin / 60, m_rem_margin % 60, 0, 0, DateTime.DayOverflow.Spillover)
        remDate = remDate.plus(0, 0, 0, m_rem_time.hour, m_rem_time.minute, 0, 0, DateTime.DayOverflow.Spillover)
        if (remDate.isInTheFuture(localZone)) {
            val evtID = java.lang.Long.parseLong(uri.lastPathSegment)
            values.apply {
                clear()
                put(Reminders.EVENT_ID, evtID)
                put(Reminders.MINUTES, remDate.numSecondsFrom(date) / 60)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            }
            m_cr.insert(Reminders.CONTENT_URI, values)
        }
    }

    private fun insertEvts(calID: Long, tasks: List<TodoListItem>?) {
        if (tasks == null) {
            return
        }
        for (item in tasks) {
            val task = item.task
            if (task.isCompleted()) continue

            var dt: DateTime?
            var text: String? = null

            // Check due date:
            if (m_sync_type and SYNC_TYPE_DUES != 0) {
                dt = task.getDueDateDT()
                if (dt != null) {
                    text = task.text
                    insertEvt(calID, dt, text, m_app.getString(R.string.calendar_sync_desc_due))
                }
            }

            // Check threshold date:
            if (m_sync_type and SYNC_TYPE_THRESHOLDS != 0) {
                dt = task.getThresholdDateDT()
                if (dt != null) {
                    if (text == null) text = task.text
                    insertEvt(calID, dt, text, m_app.getString(R.string.calendar_sync_desc_thre))
                }
            }
        }
    }

    private fun purgeEvts(calID: Long) {
        val selection = Events.CALENDAR_ID + " = ?"
        val args = arrayOf("")
        args[0] = calID.toString()
        m_cr.delete(Events.CONTENT_URI, selection, args)
    }

    private fun sync() {

        try {
            if (m_sync_type == 0) {
                if (calID >= 0) removeCalendar()
                return
            }

            if (calID < 0) {
                addCalendar()
                if (calID < 0) {
                    calendarError(null)
                    return
                }
            }

            val tl = m_app.todoList

            val tasks = tl.todoItems
            setReminderDays(m_app.reminderDays)
            setReminderTime(m_app.reminderTime)

            log.debug(TAG, "Syncing due/threshold calendar reminders...")
            purgeEvts(calID)
            insertEvts(calID, tasks)
        } catch (e: IllegalArgumentException) {
            calendarError(e)
        } catch (e: IllegalStateException) {
            log.warn(TAG, "No calendar access")
        }

    }

    private fun setSyncType(syncType: Int) {
        if (syncType == m_sync_type) return
        if (!TodoApplication.atLeastAPI(16)) {
            m_sync_type = 0
            return
        }
        m_sync_type = syncType
        syncLater()
    }

    init {
        log = Logger
        m_sync_runnable = SyncRunnable()
        m_cr = m_app.contentResolver
        m_stpe = ScheduledThreadPoolExecutor(1)
        var syncType = 0
        if (syncDues) syncType = syncType or SYNC_TYPE_DUES
        if (syncThresholds) syncType = syncType or SYNC_TYPE_THRESHOLDS
        setSyncType(syncType)
    }

    fun syncLater() {
        m_stpe.queue.clear()
        m_stpe.schedule(m_sync_runnable, SYNC_DELAY_MS.toLong(), TimeUnit.MILLISECONDS)
    }

    fun setSyncDues(bool: Boolean) {
        val syncType = if (bool) m_sync_type or SYNC_TYPE_DUES else m_sync_type and SYNC_TYPE_DUES.inv()
        setSyncType(syncType)
    }

    fun setSyncThresholds(bool: Boolean) {
        val syncType = if (bool) m_sync_type or SYNC_TYPE_THRESHOLDS else m_sync_type and SYNC_TYPE_THRESHOLDS.inv()
        setSyncType(syncType)
    }

    fun setReminderDays(days: Int) {
        m_rem_margin = days * 1440
    }

    fun setReminderTime(time: Int) {
        m_rem_time = DateTime.forTimeOnly(time / 60, time % 60, 0, 0)
    }

    companion object {
        private val PACKAGE = TodoApplication.appContext.packageName
        private val UTC = TimeZone.getTimeZone("UTC")

        private val ACCOUNT_NAME = "Simpletask Calendar"
        private val ACCOUNT_TYPE = CalendarContract.ACCOUNT_TYPE_LOCAL
        private val CAL_URI = Calendars.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true").appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT_NAME).appendQueryParameter(Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE).build()
        private val CAL_NAME = "simpletask_reminders_v34SsjC7mwK9WSVI"
        private val CAL_COLOR = Color.BLUE       // Chosen arbitrarily...
        private val EVT_DURATION_DAY = 24 * 60 * 60 * 1000  // ie. 24 hours

        private val SYNC_DELAY_MS = 1000
        private val TAG = "CalendarSync"


        val SYNC_TYPE_DUES = 1
        val SYNC_TYPE_THRESHOLDS = 2
    }
}
