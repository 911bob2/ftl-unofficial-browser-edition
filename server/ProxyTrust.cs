using System.Net;
using Microsoft.AspNetCore.HttpOverrides;

namespace Ftl.Server;
public static class ProxyTrust {
    public static void Configure(ForwardedHeadersOptions options, IEnumerable<string> entries) {
        options.KnownIPNetworks.Clear();options.KnownProxies.Clear();options.ForwardLimit=1;
        foreach(var entry in entries) {
            if(entry.Contains('/')) {
                if(!System.Net.IPNetwork.TryParse(entry,out var network)||network.PrefixLength==0)
                    throw new InvalidOperationException($"Invalid TRUSTED_PROXIES network: {entry}");
                options.KnownIPNetworks.Add(network);
            } else {
                if(!IPAddress.TryParse(entry,out var address))throw new InvalidOperationException($"Invalid TRUSTED_PROXIES address: {entry}");
                options.KnownProxies.Add(address);
            }
        }
        // Empty lists otherwise mean trust every peer in this middleware.
        options.ForwardedHeaders=options.KnownProxies.Count+options.KnownIPNetworks.Count==0
            ?ForwardedHeaders.None:ForwardedHeaders.XForwardedFor|ForwardedHeaders.XForwardedProto;
    }
}
