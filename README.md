# Caching Strategies in System Design (with Core Java & Redis)

A comprehensive reference guide and practical Core Java implementation covering caching architectures, read/write patterns, eviction policies, common distributed caching pitfalls, and decision frameworks.

---

## Table of Contents
1. [Overview & Objectives](#1-overview--objectives)
2. [Caching Layers in System Design](#2-caching-layers-in-system-design)
3. [Read & Write Strategies](#3-read--write-strategies)
   - [1. Cache-Aside (Lazy Loading)](#1-cache-aside-lazy-loading)
   - [2. Read-Through & Write-Through](#2-read-through--write-through)
   - [3. Write-Behind (Write-Back)](#3-write-behind-write-back)
   - [4. Write-Around](#4-write-around)
   - [5. Refresh-Ahead (Read-Ahead)](#5-refresh-ahead-read-ahead)
4. [Cache Eviction Policies](#4-cache-eviction-policies)
5. [Distributed Failure Modes & Mitigation](#5-distributed-failure-modes--mitigation)
6. [Strategy Selection Matrix](#6-strategy-selection-matrix)
7. [Java + Redis Project Setup & Running](#7-java--redis-project-setup--running)

---

## 1. Overview & Objectives

A **caching strategy** governs how data is stored, read, updated, and evicted in high-speed, temporary storage layers (such as RAM via Redis, Memcached, or in-process memory).

### Key Goals
- **Latency Reduction:** Sub-millisecond in-memory access vs. disk/network database access.
- **Throughput & Scalability:** Absorbs spikes and offloads repetitive read traffic from primary databases.
- **Cost Reduction:** Reduces required database compute, provisioned IOPS, and connection pools.
- **Availability:** Serves cached copies even if backend storage undergoes transient degradation.

---

## 2. Caching Layers in System Design

```mermaid
flowchart LR
    Client[Client / Browser] --> CDN[CDN / Edge Layer]
    CDN --> LB[API Gateway / Load Balancer]
    LB --> App[Application Server]
    App --> InMem[Distributed Cache\nRedis / Memcached]
    InMem -.-> DB[(Primary Database)]
    App --> DB
```

| Layer | Technologies | Target Data |
| :--- | :--- | :--- |
| **Client** | HTTP Cache Headers, LocalStorage | Static assets (JS, CSS, images), user preferences |
| **Edge / CDN** | Cloudflare, CloudFront, Akamai | Static files, geo-distributed cacheable API responses |
| **Gateway / Reverse Proxy** | Nginx, Envoy, Kong | Upstream HTTP response caching |
| **Application Layer** | Local memory (Guava, Caffeine) | Highly localized hot keys, configs |
| **Distributed Cache** | Redis, Memcached, Hazelcast | Shared session data, user profiles, aggregated queries |
| **Database Buffers** | MySQL InnoDB Pool, Postgres Shared Buffers | Raw table pages, index trees |

---

## 3. Read & Write Strategies

### 1. Cache-Aside (Lazy Loading)
The application code directly controls cache read/write/evict operations. Data is loaded into Redis only when requested on a cache miss.

```mermaid
sequenceDiagram
    autonumber
    actor App as Application
    participant Cache as Cache (Redis)
    participant DB as Database

    App->>Cache: 1. Get(key)
    alt Cache Hit
        Cache-->>App: Return cached value
    else Cache Miss
        Cache-->>App: nil / miss
        App->>DB: 2. Query data
        DB-->>App: Return record
        App->>Cache: 3. Set(key, value, TTL)
        App-->>App: Use data
    end
```

- **Java Source:** [`CacheAsideUserService.java`](src/main/java/com/example/caching/strategies/CacheAsideUserService.java)
- **Pros:** Memory-efficient (only requested keys are cached); resilient (system falls back to DB if Redis is down).
- **Cons:** Cache miss latency penalty (3 round trips); potential for stale data if database is updated without cache eviction.

---

### 2. Read-Through & Write-Through
The application interacts only with a cache abstraction. The cache is responsible for synchronizing with the database.

- **Read-Through:** On cache miss, the cache layer fetches from the DB transparently, stores it, and returns it.
- **Write-Through:** Every write synchronously persists to both Database and Redis before acknowledging success.

```mermaid
sequenceDiagram
    autonumber
    actor App as Application
    participant Cache as Cache Layer (Redis)
    participant DB as Database

    Note over App,DB: Write-Through Flow
    App->>Cache: 1. Put(key, value)
    Cache->>DB: 2. Write to DB (Synchronous)
    DB-->>Cache: Acknowledge write
    Cache-->>App: 3. Acknowledge write complete
```

- **Java Source:** [`ReadAndWriteThroughCache.java`](src/main/java/com/example/caching/strategies/ReadAndWriteThroughCache.java)
- **Pros:** High data consistency; subsequent reads are guaranteed cache hits.
- **Cons:** Higher write latency due to dual synchronous writes.

---

### 3. Write-Behind (Write-Back)
Data is written immediately to the Redis cache and acknowledged to the caller. A background worker / queue asynchronously flushes batched writes to the database.

```mermaid
sequenceDiagram
    autonumber
    actor App as Application
    participant Cache as Cache (Redis)
    participant Queue as Memory Queue
    participant DB as Database

    App->>Cache: 1. Write(key, value)
    App->>Queue: 2. Enqueue write task
    App-->>App: 3. Acknowledge write (Immediate)
    Note over Queue,DB: Asynchronous Batch Worker
    Queue->>DB: 4. Flush batch writes to DB
```

- **Java Source:** [`WriteBehindCache.java`](src/main/java/com/example/caching/strategies/WriteBehindCache.java)
- **Pros:** Ultra-low write latency; handles immense write traffic by batching database writes.
- **Cons:** Risk of permanent data loss if the cache/queue crashes before flushing to persistent storage.

---

### 4. Write-Around
Data is written directly to the database, completely bypassing the Redis cache. The cache is populated only when the data is subsequently read (via Cache-Aside).

```mermaid
sequenceDiagram
    autonumber
    actor App as Application
    participant Cache as Cache (Redis)
    participant DB as Database

    App->>DB: 1. Write data directly to DB
    DB-->>App: 2. Acknowledge write
    App->>Cache: 3. Invalidate/Evict stale key (if present)
    Note over Cache: Cache not populated with new value
```

- **Java Source:** [`WriteAroundUserService.java`](src/main/java/com/example/caching/strategies/WriteAroundUserService.java)
- **Pros:** Prevents cache pollution for write-heavy data that is rarely read (e.g. audit logs, billing archives).
- **Cons:** The first read on recently written data will always result in a cache miss.

---

### 5. Refresh-Ahead (Read-Ahead)
The cache predicts hot item expiration. If a key is accessed when its remaining TTL is below a threshold (e.g., within 20% of expiration), an asynchronous background task proactively re-fetches fresh data from the database and extends the TTL in Redis before it ever expires.

```mermaid
sequenceDiagram
    autonumber
    actor App as Application
    participant Cache as Cache (Redis)
    participant BgTask as Async Worker
    participant DB as Database

    App->>Cache: 1. Get(key)
    Cache-->>App: 2. Return cached value (Hit)
    Note over App,BgTask: Remaining TTL < 20% (Threshold Passed)
    App->>BgTask: 3. Trigger proactive refresh (async)
    BgTask->>DB: 4. Fetch fresh record from DB
    DB-->>BgTask: 5. Return updated record
    BgTask->>Cache: 6. Re-populate Redis with new TTL
```

- **Java Source:** [`RefreshAheadCacheService.java`](src/main/java/com/example/caching/strategies/RefreshAheadCacheService.java)
- **Pros:** Completely eliminates cache-miss latency for hot keys; prevents Cache Stampedes / Thundering Herd problems.
- **Cons:** Inaccurate access predictions can cause unnecessary database load.

---

## 4. Cache Eviction Policies

| Policy | Mechanism | Best Use Case |
| :--- | :--- | :--- |
| **LRU (Least Recently Used)** | Discards items that have not been accessed for the longest time. | Standard read-heavy web applications. |
| **LFU (Least Frequently Used)** | Tracks access frequency; discards items with lowest access counts. | Workloads with stable long-term hot items. |
| **FIFO (First-In First-Out)** | Discards items in the exact order they were inserted. | Time-series, sequential, or append-only data. |
| **TTL (Time-to-Live)** | Automatically expires keys after a defined duration. | Volatile tokens, session data, rate-limit counters. |
| **Random Eviction** | Randomly evicts keys to reclaim memory. | Uniform access distributions. |

---

## 5. Distributed Failure Modes & Mitigation

| Failure Mode | Cause | Mitigation Strategy |
| :--- | :--- | :--- |
| **Cache Stampede (Thundering Herd)** | Hot key expires under heavy concurrent load, causing massive DB spikes. | Mutex locking / Singleflight pattern; `Refresh-Ahead`; Probabilistic early expiration (XFetch). |
| **Cache Avalanche** | Large volume of keys expire simultaneously or entire cache cluster restarts. | Add random TTL jitter (e.g. TTL ± 10%); Use clustered cache with replication. |
| **Cache Penetration** | Repeated requests for non-existent keys bypass cache and hit DB directly. | Cache null/empty values with low TTL; Use Bloom Filters before cache lookups. |
| **Data Inconsistency (Stale Cache)** | DB updated but cache invalidation fails or out-of-order writes occur. | Change Data Capture (CDC via Debezium); Transactional Outbox pattern. |

---

## 6. Strategy Selection Matrix

| Strategy | Read Latency | Write Latency | Data Consistency | Best Fit For |
| :--- | :--- | :--- | :--- | :--- |
| **Cache-Aside** | Low (Hit) / High (Miss) | Fast (DB + Evict) | Eventual | General-purpose web apps |
| **Write-Through** | Ultra-Low | Higher (DB + Cache) | High | Financial/eCommerce product catalogs |
| **Write-Behind** | Ultra-Low | Ultra-Low | Eventual (Lag risk) | High-volume ingestion, telemetry, activity logs |
| **Write-Around** | High on 1st read | Fast | High in DB | Write-heavy, rarely-read records (audit trails) |
| **Refresh-Ahead** | Ultra-Low (Near 0 misses) | Normal | High | Ultra-hot keys (trending feeds, live leaderboards) |

---

## 7. Java + Redis Project Setup & Running

### Requirements
- Java 17+
- Maven 3.8+
- Redis Server running on `localhost:6379`

### Run with Docker:
```powershell
# 1. Start Redis
docker run -d -p 6379:6379 --name redis-caching-demo redis:alpine

# 2. Compile and run the Java demo
mvn compile exec:java -Dexec.mainClass="com.example.caching.Main"
```
