package com.aurishybrid.launcher.media

import android.service.notification.NotificationListenerService

/**
 * Пустой слушатель уведомлений. Сами уведомления не читаются: включённый слушатель —
 * единственный способ без root получить от Android список активных медиасессий.
 */
class MediaListenerService : NotificationListenerService()
