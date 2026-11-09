using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using MediaBrowser.Model.Tasks;

namespace Jellyfin.Plugin.Voidcast.Services;

/// <summary>The weekly scan for new releases; shows up under Dashboard → Scheduled Tasks, where its schedule can be changed.</summary>
public class NewReleasesScanTask : IScheduledTask
{
    private readonly NewReleasesService _newReleases;

    public NewReleasesScanTask(NewReleasesService newReleases)
    {
        _newReleases = newReleases;
    }

    public string Name => "Scan for new releases";

    public string Key => "VoidcastNewReleases";

    public string Description => "Looks on YouTube Music for releases of the library's artists that aren't in the library yet.";

    public string Category => "Voidcast";

    public Task ExecuteAsync(IProgress<double> progress, CancellationToken cancellationToken) =>
        _newReleases.ScanAsync(progress, cancellationToken);

    // An interval rather than a weekday, as that also runs soon after the plugin got installed
    public IEnumerable<TaskTriggerInfo> GetDefaultTriggers() =>
        [new TaskTriggerInfo { Type = TaskTriggerInfoType.IntervalTrigger, IntervalTicks = TimeSpan.FromDays(7).Ticks }];
}
