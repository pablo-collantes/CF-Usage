package usage

import (
	"fmt"
	"strings"
)

// Row limit per dataset. A dataset that returns exactly this many rows was cut off.
const Limit = 10000

type dataset struct {
	alias, name, dims, fields string
}

// The flow datasets: one GraphQL Analytics dataset per billable product.
var flowDatasets = []dataset{
	{"workers", "workersInvocationsAdaptive", "scriptName", "sum { requests errors cpuTimeUs subrequests }"},
	{"pages", "pagesFunctionsInvocationsAdaptiveGroups", "scriptName", "sum { requests errors }"},
	{"doInvocations", "durableObjectsInvocationsAdaptiveGroups", "namespaceId type", "sum { requests }"},
	{"doPeriodic", "durableObjectsPeriodicGroups", "namespaceId",
		"sum { duration rowsRead rowsWritten storageReadUnits storageWriteUnits storageDeletes inboundWebsocketMsgCount }"},
	{"d1", "d1AnalyticsAdaptiveGroups", "databaseId", "sum { rowsRead rowsWritten }"},
	{"r2", "r2OperationsAdaptiveGroups", "bucketName actionType", "sum { requests }"},
	{"queues", "queueMessageOperationsAdaptiveGroups", "queueId actionType", "sum { billableOperations }"},
	{"kv", "kvOperationsAdaptiveGroups", "namespaceId actionType", "sum { requests }"},
}

// Storage datasets are daily gauges, always grouped by date.
var storageDatasets = []dataset{
	{"d1Storage", "d1StorageAdaptiveGroups", "databaseId date", "max { databaseSizeBytes }"},
	{"r2Storage", "r2StorageAdaptiveGroups", "bucketName storageClass date", "max { payloadSize metadataSize objectCount }"},
	{"doSqlStorage", "durableObjectsSqlStorageGroups", "namespaceId date", "max { storedBytes }"},
	{"doKvStorage", "durableObjectsStorageGroups", "date", "max { storedBytes }"},
	{"kvStorage", "kvStorageAdaptiveGroups", "namespaceId date", "max { byteCount keyCount }"},
}

// FreshDatasets are checked for the newest minute that has data, to show how far
// behind analytics runs.
var FreshDatasets = []string{
	"workersInvocationsAdaptive", "pagesFunctionsInvocationsAdaptiveGroups",
	"durableObjectsInvocationsAdaptiveGroups", "durableObjectsPeriodicGroups",
	"d1AnalyticsAdaptiveGroups", "d1StorageAdaptiveGroups", "r2OperationsAdaptiveGroups",
	"r2StorageAdaptiveGroups", "queueMessageOperationsAdaptiveGroups", "kvOperationsAdaptiveGroups",
}

// KeyFreshDatasets decide dataAsOf: the busiest datasets, whose lag is the lag
// that matters for alerts. R2 is left out because its operations dataset always
// runs 15-20 minutes behind.
var KeyFreshDatasets = []string{
	"workersInvocationsAdaptive", "durableObjectsInvocationsAdaptiveGroups", "d1AnalyticsAdaptiveGroups",
}

// AccountQuery builds the account usage query over [$start, $end). bucketDim adds
// a time dimension to every flow dataset ("datetimeHour"); storage adds the daily
// storage gauges; fresh adds the freshness probes over [$fstart, $fend).
func AccountQuery(bucketDim string, storage, fresh bool) string {
	var b strings.Builder
	b.WriteString("query Usage($acct: string!, $start: Time!, $end: Time!")
	if fresh {
		b.WriteString(", $fstart: Time!, $fend: Time!")
	}
	b.WriteString(") {\n  viewer { accounts(filter: {accountTag: $acct}) {\n")
	write := func(ds dataset, dims string) {
		fmt.Fprintf(&b, "    %s: %s(limit: %d, filter: {datetime_geq: $start, datetime_lt: $end}) { dimensions { %s } %s }\n",
			ds.alias, ds.name, Limit, dims, ds.fields)
	}
	for _, ds := range flowDatasets {
		dims := ds.dims
		if bucketDim != "" {
			dims += " " + bucketDim
		}
		write(ds, dims)
	}
	if storage {
		for _, ds := range storageDatasets {
			write(ds, ds.dims)
		}
	}
	if fresh {
		for _, name := range FreshDatasets {
			fmt.Fprintf(&b, "    fresh_%s: %s(limit: 1, orderBy: [datetimeMinute_DESC], filter: {datetime_geq: $fstart, datetime_lt: $fend}) { dimensions { datetimeMinute } }\n",
				name, name)
		}
	}
	b.WriteString("  } }\n}")
	return b.String()
}

// ColoQuery is a script's requests per Cloudflare data centre.
const ColoQuery = `query Colos($acct: string!, $script: string!, $start: Time!, $end: Time!) {
  viewer { accounts(filter: {accountTag: $acct}) {
    colos: workersInvocationsAdaptive(limit: 15, orderBy: [sum_requests_DESC],
        filter: {scriptName: $script, datetime_geq: $start, datetime_lt: $end}) {
      dimensions { coloCode } sum { requests }
    }
  } }
}`

// SitesQuery lists every hostname with visitor traffic.
const SitesQuery = `query Sites($zone: string!, $start: Time!, $end: Time!) {
  viewer { zones(filter: {zoneTag: $zone}) {
    hosts: httpRequestsAdaptiveGroups(limit: 500, orderBy: [count_DESC],
        filter: {datetime_geq: $start, datetime_lt: $end, requestSource: "eyeball"}) {
      count dimensions { clientRequestHTTPHost } sum { edgeResponseBytes } ratio { status4xx status5xx }
    }
    blocked: httpRequestsAdaptiveGroups(limit: 500,
        filter: {datetime_geq: $start, datetime_lt: $end, requestSource: "eyeball", securityAction: "block"}) {
      count dimensions { clientRequestHTTPHost }
    }
    cached: httpRequestsAdaptiveGroups(limit: 500,
        filter: {datetime_geq: $start, datetime_lt: $end, requestSource: "eyeball", cacheStatus: "hit"}) {
      count dimensions { clientRequestHTTPHost }
    }
  } }
}`

// SparkQuery is a time series per host for the given hosts. Hosts x buckets must
// stay under Limit rows, so callers pass only the busiest hosts.
func SparkQuery(bucketDim string) string {
	return fmt.Sprintf(`query Spark($zone: string!, $start: Time!, $end: Time!, $hosts: [string!]) {
  viewer { zones(filter: {zoneTag: $zone}) {
    spark: httpRequestsAdaptiveGroups(limit: %d,
        filter: {datetime_geq: $start, datetime_lt: $end, requestSource: "eyeball", clientRequestHTTPHost_in: $hosts}) {
      count dimensions { clientRequestHTTPHost %s }
    }
  } }
}`, Limit, bucketDim)
}

// SiteQuery is the traffic detail of one hostname.
func SiteQuery(bucketDim string) string {
	f := `filter: {datetime_geq: $start, datetime_lt: $end, requestSource: "eyeball", clientRequestHTTPHost: $host`
	return fmt.Sprintf(`query Site($zone: string!, $host: string!, $start: Time!, $end: Time!) {
  viewer { zones(filter: {zoneTag: $zone}) {
    total: httpRequestsAdaptiveGroups(limit: 1, %[1]s}) {
      count sum { edgeResponseBytes } ratio { status4xx status5xx }
    }
    series: httpRequestsAdaptiveGroups(limit: 2000, %[1]s}) { count dimensions { %[2]s } }
    blockedSeries: httpRequestsAdaptiveGroups(limit: 2000, %[1]s, securityAction: "block"}) { count dimensions { %[2]s } }
    paths: httpRequestsAdaptiveGroups(limit: 50, orderBy: [count_DESC], %[1]s}) { count dimensions { clientRequestPath } }
    countries: httpRequestsAdaptiveGroups(limit: 30, orderBy: [count_DESC], %[1]s}) { count dimensions { clientCountryName } }
    statuses: httpRequestsAdaptiveGroups(limit: 50, orderBy: [count_DESC], %[1]s}) { count dimensions { edgeResponseStatus } }
    security: httpRequestsAdaptiveGroups(limit: 20, orderBy: [count_DESC], %[1]s}) { count dimensions { securityAction } }
    agents: httpRequestsAdaptiveGroups(limit: 15, orderBy: [count_DESC], %[1]s}) { count dimensions { userAgentBrowser } }
    cache: httpRequestsAdaptiveGroups(limit: 20, orderBy: [count_DESC], %[1]s}) { count dimensions { cacheStatus } }
  } }
}`, f, bucketDim)
}
