package io.th0rgal.oraxen.pack.upload;

import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.config.Message;
import io.th0rgal.oraxen.config.Settings;
import io.th0rgal.oraxen.pack.dispatch.AdvancedPackSender;
import io.th0rgal.oraxen.pack.dispatch.BukkitPackSender;
import io.th0rgal.oraxen.pack.dispatch.PackSender;
import io.th0rgal.oraxen.pack.generation.ResourcePack;
import io.th0rgal.oraxen.pack.receive.PackReceiver;
import io.th0rgal.oraxen.pack.upload.hosts.HostingProvider;
import io.th0rgal.oraxen.pack.upload.hosts.Polymath;
import io.th0rgal.oraxen.pack.upload.hosts.Sh;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.ProviderNotFoundException;
import java.util.List;
import java.util.Locale;

public class UploadManager {

    private final Plugin plugin;
    private final boolean enabled;
    private final HostingProvider hostingProvider;
    private PackSender packSender;

    private PackReceiver receiver;
    private PackSender sender;

    public UploadManager(final Plugin plugin) {
        this.plugin = plugin;
        enabled = Settings.UPLOAD.toBool();
        hostingProvider = createHostingProvider();
    }

    public HostingProvider getHostingProvider() {
        return hostingProvider;
    }

    public PackSender getSender() {
        return packSender;
    }

    public void uploadAsyncAndSendToPlayers(final ResourcePack resourcePack) {
        uploadAsyncAndSendToPlayers(resourcePack, false);
    }

    public void uploadAsyncAndSendToPlayers(final ResourcePack resourcePack, final boolean updateSend) {
        if (!enabled)
            return;
        if (Settings.RECEIVE_ENABLED.toBool() && receiver == null)
            Bukkit.getPluginManager().registerEvents(receiver = new PackReceiver(), plugin);
        final long time = System.currentTimeMillis();
        Message.PACK_UPLOADING.log("prefix", Message.PREFIX.toString());
        Bukkit.getScheduler().runTaskAsynchronously(OraxenPlugin.get(), () -> {
            if (!hostingProvider.uploadPack(resourcePack.getFile())) {
                Message.PACK_NOT_UPLOADED.log();
                return;
            }
            Message.PACK_UPLOADED.log(
                    "url", hostingProvider.getPackURL(), "delay", String.valueOf(System.currentTimeMillis() - time));

            if ((Settings.SEND_PACK.toBool() || Settings.SEND_JOIN_MESSAGE.toBool()) && sender == null) {
                packSender = (OraxenPlugin.getProtocolLib() && Settings.SEND_PACK_ADVANCED.toBool())
                        ? new AdvancedPackSender(hostingProvider) : new BukkitPackSender(hostingProvider);
                packSender.register();
            }
            /* Too much pain for people trying to configure mechanics
            if ((boolean) Pack.SEND_PACK.getValue() && updateSend)
                for (Player player : Bukkit.getOnlinePlayers())
                    PackDispatcher.sendPack(player);
            */
        });
    }

    private HostingProvider createHostingProvider() {
        switch (Settings.UPLOAD_TYPE.toString().toLowerCase(Locale.ENGLISH)) {
            case "polymath":
                return new Polymath(Settings.POLYMATH_SERVER.toString());
            case "sh":
            case "cmd":
                final ConfigurationSection opt = (ConfigurationSection) Settings.UPLOAD_OPTIONS.getValue();
                final List<String> args = opt.getStringList("args");
                if (args.isEmpty())
                    throw new ProviderNotFoundException("No command line.");
                final String placeholder = opt.getString("placeholder", "${file}");
                return new Sh(Sh.path(placeholder, args));
            case "external":
                final Class<?> target;
                final ConfigurationSection options = (ConfigurationSection) Settings.UPLOAD_OPTIONS.getValue();
                final String klass = options.getString("class");
                if (klass == null)
                    throw new ProviderNotFoundException("No provider set.");
                try {
                    target = Class.forName(klass);
                } catch (final Throwable any) {
                    final ProviderNotFoundException error = new ProviderNotFoundException("Provider not found: " + klass);
                    error.addSuppressed(any);
                    throw error;
                }
                if (!HostingProvider.class.isAssignableFrom(target))
                    throw new ProviderNotFoundException(target + " is not a valid HostingProvider.");
                final Class<? extends HostingProvider> implement = target.asSubclass(HostingProvider.class);
                Constructor<? extends HostingProvider> constructor;
                try {
                    try {
                        constructor = implement.getConstructor(ConfigurationSection.class);
                    } catch (final Exception notFound) {
                        try {
                            constructor = implement.getConstructor();
                        } catch (final Exception ignore) {
                            // For catching reasons
                            throw (ProviderNotFoundException) new ProviderNotFoundException("Invalid provider: " + target).initCause(ignore); // Use (Lorg/bukkit/configuration/ConfigurationSection;)V to Exception
                        }
                    }
                } catch (final Exception e) {
                    throw (ProviderNotFoundException) new ProviderNotFoundException("Cannot found constructor in " + target)
                            .initCause(e);
                }
                try {
                    return constructor.getParameterCount() == 0 ? constructor.newInstance()
                            : constructor.newInstance(options);
                } catch (final InstantiationException e) {
                    throw (ProviderNotFoundException) new ProviderNotFoundException("Cannot alloc instance for " + target)
                            .initCause(e);
                } catch (final IllegalAccessException e) {
                    throw (ProviderNotFoundException) new ProviderNotFoundException("Failed to access " + target)
                            .initCause(e);
                } catch (final InvocationTargetException e) {
                    throw (ProviderNotFoundException) new ProviderNotFoundException("Exception in allocating instance.")
                            .initCause(e.getCause());
                }
            default:
                throw new ProviderNotFoundException("Unknown provider type: " + Settings.UPLOAD_TYPE);
        }

    }

}
