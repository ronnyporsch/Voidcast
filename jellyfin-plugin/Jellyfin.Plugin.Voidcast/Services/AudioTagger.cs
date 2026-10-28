using System.Globalization;
using System.Linq;
using Jellyfin.Plugin.Voidcast.Api;
using TagLib;

namespace Jellyfin.Plugin.Voidcast.Services;

/// <summary>Writes <see cref="TrackTags"/> the way MusicBrainz Picard would, using the server's TagLibSharp.</summary>
internal static class AudioTagger
{
    public static void Write(string path, TrackTags tags, byte[]? jpegCover)
    {
        using var file = TagLib.File.Create(path);
        var tag = file.Tag;
        tag.Title = tags.Title;
        tag.Performers = string.IsNullOrWhiteSpace(tags.Artist) ? [] : [tags.Artist];
        tag.Album = tags.Album;
        tag.Genres = tags.Genre != null ? [tags.Genre] : [];
        tag.AlbumArtists = tags.AlbumArtist != null ? [tags.AlbumArtist] : [];
        tag.Track = (uint)(tags.TrackNumber ?? 0);
        tag.TrackCount = (uint)(tags.TotalTracks ?? 0);
        tag.Year = (uint)(tags.Year ?? 0);
        tag.Comment = tags.Comment;
        if (jpegCover != null)
        {
            tag.Pictures =
            [
                new Picture(new ByteVector(jpegCover))
                {
                    Type = PictureType.FrontCover,
                    MimeType = "image/jpeg",
                    Description = "Album cover",
                },
            ];
        }

        // Multi-valued artists and release info have no generic TagLib property
        if (file.GetTag(TagTypes.Xiph) is TagLib.Ogg.XiphComment xiph)
        {
            xiph.SetField("ARTISTS", tags.Artists.ToArray());
            xiph.SetField("ALBUMARTISTS", tags.AlbumArtists.ToArray());
            SetXiph(xiph, "TOTALTRACKS", tags.TotalTracks?.ToString(CultureInfo.InvariantCulture));
            SetXiph(xiph, "RELEASETYPE", tags.ReleaseType);
            SetXiph(xiph, "MEDIA", tags.Media);
        }

        if (file.GetTag(TagTypes.Apple) is TagLib.Mpeg4.AppleTag apple)
        {
            // Picard's iTunes freeform atom names
            apple.SetDashBoxes("com.apple.iTunes", "ARTISTS", tags.Artists.ToArray());
            apple.SetDashBoxes("com.apple.iTunes", "ALBUMARTISTS", tags.AlbumArtists.ToArray());
            if (tags.ReleaseType != null)
            {
                apple.SetDashBox("com.apple.iTunes", "MusicBrainz Album Type", tags.ReleaseType);
            }

            if (tags.Media != null)
            {
                apple.SetDashBox("com.apple.iTunes", "MEDIA", tags.Media);
            }
        }

        if (file.GetTag(TagTypes.Id3v2) is TagLib.Id3v2.Tag id3)
        {
            SetTxxx(id3, "ARTISTS", tags.Artists.ToArray());
            SetTxxx(id3, "ALBUMARTISTS", tags.AlbumArtists.ToArray());
            SetTxxx(id3, "RELEASETYPE", tags.ReleaseType == null ? [] : [tags.ReleaseType]);
            if (tags.Media != null)
            {
                id3.SetTextFrame("TMED", tags.Media);
            }
        }

        file.Save();
    }

    private static void SetXiph(TagLib.Ogg.XiphComment xiph, string key, string? value)
    {
        if (value != null)
        {
            xiph.SetField(key, value);
        }
    }

    private static void SetTxxx(TagLib.Id3v2.Tag id3, string description, string[] values)
    {
        if (values.Length == 0)
        {
            return;
        }

        var frame = TagLib.Id3v2.UserTextInformationFrame.Get(id3, description, true);
        frame.Text = values;
    }
}
