# ClamAV Filter

A Jahia module that scans uploaded files against a [ClamAV](https://www.clamav.net/) antivirus daemon and blocks any upload containing detected malware.

## Requirements

- Jahia 8.2.1 or later
- A running ClamAV daemon reachable from the Jahia server
- `graphql-dxm-provider` module

## Installation

1. In Jahia, go to **Administration → Server settings → System components → Modules**
2. Upload the JAR **clamav-filter-X.X.X.jar**
3. Verify the module status is **Started**

## Configuration

The module reads its settings from the OSGi configuration file:

```
$JAHIA_DATA_DIR/karaf/etc/org.jahia.community.clamav.cfg
```

| Property             | Default     | Description                                                    |
|----------------------|-------------|----------------------------------------------------------------|
| `host`               | `localhost`  | Hostname or IP address of the ClamAV daemon                  |
| `port`               | `3310`       | Port of the ClamAV daemon                                     |
| `connection_timeout` | `2000`       | Maximum time (ms) to wait when connecting to the daemon       |
| `read_timeout`       | `20000`      | Maximum time (ms) to wait for the scan response               |

Changes to the configuration file are applied immediately — no restart required.

### Admin UI

Settings can also be edited directly in the Jahia administration panel:

**Administration → Server Health → ClamAV Antivirus Settings**

The UI allows editing all four settings, saving them, and testing the connection to the daemon. The connection is tested automatically when the page opens. The file scan test section is disabled when the daemon is not reachable.

## How it works

1. When a file is uploaded through Jahia (Media Manager, Forms, etc.), the upload filter intercepts the stream.
2. The file content is forwarded to the ClamAV daemon over TCP using the `INSTREAM` command.
3. If ClamAV reports a threat, the upload is rejected and the virus signature is logged.
4. If ClamAV is unreachable (daemon down, wrong host/port, timeout), the upload is **blocked by default** to prevent unscanned files from reaching the repository.

## Testing

Start a local ClamAV daemon with Docker:

```bash
docker run --interactive --tty --rm --publish 3310:3310 --name clamav clamav/clamav:stable
```

Wait for the container to print `socket found, clamd started.` before testing.

To trigger a detection, use the [EICAR test file](https://www.eicar.org/download-anti-malware-testfile/) — a standard, harmless file that every compliant antivirus engine flags as malware.

## Building from source

Requirements: JDK 17+, Maven 3.x, Node.js v22, Yarn v1.22

```bash
mvn clean install
```

The frontend assets are built automatically via the `frontend-maven-plugin` as part of the Maven build.
