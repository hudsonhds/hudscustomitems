package com.hudscustomitems.customitems.service;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Registers listeners with per-handler exception tracking hooks.
 */
public final class SafeListenerRegistrar {
  private SafeListenerRegistrar() {
  }

  /**
   * Registers all {@link EventHandler} methods on one listener.
   *
   * @param pluginManager plugin manager
   * @param listener listener instance
   * @param plugin owner plugin
   * @param telemetryService telemetry service
   * @return registered handler count
   */
  public static int register(
      PluginManager pluginManager,
      Listener listener,
      JavaPlugin plugin,
      TelemetryService telemetryService) {
    int registered = 0;
    for (Method method : listener.getClass().getDeclaredMethods()) {
      EventHandler handler = method.getAnnotation(EventHandler.class);
      if (handler == null || !isEventHandlerMethod(method)) {
        continue;
      }
      method.setAccessible(true);
      @SuppressWarnings("unchecked")
      Class<? extends Event> eventClass = (Class<? extends Event>) method.getParameterTypes()[0];
      EventExecutor executor = buildExecutor(listener, method, eventClass, telemetryService);
      pluginManager.registerEvent(
          eventClass,
          listener,
          handler.priority(),
          executor,
          plugin,
          handler.ignoreCancelled());
      registered++;
    }
    return registered;
  }

  private static boolean isEventHandlerMethod(Method method) {
    Class<?>[] parameters = method.getParameterTypes();
    return parameters.length == 1 && Event.class.isAssignableFrom(parameters[0]);
  }

  private static EventExecutor buildExecutor(
      Listener listener,
      Method method,
      Class<? extends Event> eventClass,
      TelemetryService telemetryService) {
    return (ignoredListener, event) -> {
      if (!eventClass.isInstance(event)) {
        return;
      }
      try {
        method.invoke(listener, event);
      } catch (InvocationTargetException throwable) {
        Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
        telemetryService.recordListenerException(method.getName(), cause);
        throw new EventException(cause);
      } catch (IllegalAccessException throwable) {
        telemetryService.recordListenerException(method.getName(), throwable);
        throw new EventException(throwable);
      }
    };
  }
}
