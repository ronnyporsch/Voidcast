using Jellyfin.Plugin.Voidcast.Services;
using MediaBrowser.Controller;
using MediaBrowser.Controller.Plugins;
using Microsoft.Extensions.DependencyInjection;

namespace Jellyfin.Plugin.Voidcast;

public class PluginServiceRegistrator : IPluginServiceRegistrator
{
    public void RegisterServices(IServiceCollection serviceCollection, IServerApplicationHost applicationHost)
    {
        serviceCollection.AddSingleton<YtDlpService>();
        serviceCollection.AddSingleton<YouTubeMusicClient>();
        serviceCollection.AddSingleton<DownloadedTracks>();
        serviceCollection.AddSingleton<DownloadManager>();
        serviceCollection.AddSingleton<NewReleasesService>();
        serviceCollection.AddSingleton<WrappedService>();
    }
}
