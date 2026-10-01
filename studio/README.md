# OpenRune Studio backend

The `:studio` module owns the versioned Content Studio contract and the first
server-side implementation of project persistence.

## Current scope

Implemented:

- Project Format v1 / Edit Format v1 Kotlin parity
- filesystem-backed project storage
- project list/create/load/save/delete/import/export
- loopback HTTP transport for those project operations

Not implemented yet:

- cache mutation or publishing
- cache builds
- terrain or loc encoding
- OpenRune-backed CacheSource
- OpenRune-backed WorldSource

Project persistence remains separate from the running game world and cache.

## Storage

Projects are stored under:

```text
.data/studio/projects
```

Project ids are never used as filesystem paths. They are mapped to SHA-256
filenames before access.

Saves use a same-directory temporary file and atomic replacement when the
filesystem supports it. Creates/imports use conflict-safe moves so an existing
project id is not overwritten.

## HTTP service

The Studio HTTP service is registered with the normal OpenRune service
lifecycle but is disabled by default.

Enable it with:

```text
OPENRUNE_STUDIO_ENABLED=true
```

Optional settings:

```text
OPENRUNE_STUDIO_PORT=8765
OPENRUNE_STUDIO_PROJECT_ROOT=.data/studio/projects
OPENRUNE_STUDIO_SESSION_FILE=.data/studio/session.json
```

Equivalent JVM properties are:

```text
openrune.studio.enabled
openrune.studio.port
openrune.studio.projectRoot
openrune.studio.sessionFile
```

When enabled, the service binds only to `127.0.0.1`.

A per-launch bearer token and endpoint are written to:

```text
.data/studio/session.json
```

On POSIX filesystems the descriptor is restricted to the current owner when
supported.

## Security boundary

The project API:

- accepts only loopback `Host` values
- rejects non-loopback browser `Origin` values
- requires the per-launch bearer token for project operations
- allows unauthenticated loopback health checks
- accepts no arbitrary filesystem paths
- limits request bodies to 16 MiB
- exposes no cache build, mutation, publish, or process-execution endpoints

The session token is supplied as:

```http
Authorization: Bearer <token>
```

## API

Base path:

```text
/studio/v1
```

Routes:

```text
GET    /health
GET    /projects
POST   /projects
POST   /projects/import
GET    /projects/{id}
PUT    /projects/{id}
DELETE /projects/{id}
GET    /projects/{id}/export
```

`POST /projects` accepts:

```json
{
  "id": "optional-project-id",
  "name": "Project name",
  "base": {
    "kind": "cache",
    "game": "oldschool",
    "revision": 240
  }
}
```

`edits` may also be supplied as a valid Edit Format v1 batch.

`PUT /projects/{id}` and `POST /projects/import` consume complete Project
Format v1 documents.

## Integration direction

The browser should eventually use an `OpenRuneProjectStore` adapter that
implements the same frontend `ProjectStore` interface as the local IndexedDB
store.

Do not make the Svelte UI call these routes directly.
