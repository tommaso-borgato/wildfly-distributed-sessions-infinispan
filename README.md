# WildFly Distributed Cache + Infinispan Server

A WildFly/JBoss EAP 8.x application which is provisioned by the `wildfly-maven-plugin` and that uses the
web-clustering layer to store session data in a distributed cache which is backed by an Infinispan Server service.

The WildFly/EAP Maven plugin is configured to build the application and trimmed server, based on required feature packs.


## Manual Setup on OpenShift

### Deploy Infinispan Operator

https://infinispan.org/docs/infinispan-operator/main/operator.html#installation

### Create secret with Infinispan custom credentials

https://infinispan.org/docs/infinispan-operator/main/operator.html#adding-credentials_authn

```bash
$ cat /tmp/identities.yaml
credentials:
- username: foo
  password: bar

oc create secret generic connect-secret --from-file=/tmp/identities.yaml
```

### Deploy Infinispan Server

/tmp/example-infinispan.yaml:
```yaml
apiVersion: infinispan.org/v1
kind: Infinispan
metadata:
  name: example-infinispan
  labels:
   app: datagrid
   namespace: datagrid-operator
spec:
  security:
    endpointSecretName: connect-secret
  replicas: 1
```

```bash
oc apply -f /tmp/example-infinispan.yaml
```

NOTE: after deployment, the following is added which points to the operator generated TLS key:

```yaml
spec:
  security:
    endpointAuthentication: true
    endpointEncryption:
      certSecretName: example-infinispan-cert-secret
      certServiceName: service.beta.openshift.io
      clientCert: None
      type: Service
    endpointSecretName: connect-secret
```

#### Get Infinispan Server admin user

```bash
oc get secret example-infinispan-generated-operator-secret -o jsonpath="{.data.identities\.yaml}" | base64 --decode
credentials:
- username: operator
  password: XXXXXXXXXXXXXXXX
  roles:
    - admin
    - controlRole
````

#### Expose Infinispan Server admin console

```yaml
kind: Route
apiVersion: route.openshift.io/v1
metadata:
  name: example-infinispan-admin
spec:
  to:
    kind: Service
    name: example-infinispan-admin
    weight: 100
  port:
    targetPort: infinispan-adm
```

### Extract Infinispan Certificate

Extract the auto-generated certificate and store it in a secret to be used by WildFly:
```bash
oc get secret example-infinispan-cert-secret -o jsonpath='{.data.tls\.crt}' | base64 --decode > /tmp/tls.crt
oc create secret generic connect-secret-crt --from-file=/tmp/tls.crt
```

This is mounted into the WildFly Pod (see `charts/distributed-sessions-infinispan.yaml`):

```yaml
  volumes:
    - name: connect-secret-crt-volume
      secret:
        secretName: connect-secret-crt
  volumeMounts:
    - name: connect-secret-crt-volume
      mountPath: /distributed-sessions-infinispan
```

and used to configure WildFly (see `scripts/script.cli`):

```
/subsystem=infinispan/remote-cache-container=rhdg-container:write-attribute(name=properties, value={infinispan.client.hotrod.use_auth=true,infinispan.client.hotrod.auth_username=foo,infinispan.client.hotrod.auth_password=bar,infinispan.client.hotrod.auth_server_name="example-infinispan",infinispan.client.hotrod.sasl_properties.javax.security.sasl.qop=auth,infinispan.client.hotrod.sasl_mechanism=SCRAM-SHA-512,infinispan.client.hotrod.sni_host_name="example-infinispan",infinispan.client.hotrod.ssl_hostname_validation=false,infinispan.client.hotrod.trust_store_filename=/distributed-sessions-infinispan/tls.crt}
```

### Deploy WildFly using Helm

```bash
helm install distributed-sessions-infinispan -f charts/distributed-sessions-infinispan.yaml wildfly/wildfly
```

if needed, uninstall like this:
```bash
helm uninstall distributed-sessions-infinispan
```

## Manual Setup locally

### Stat Infinispan

```bash
podman run -it --rm --network=host --name=my-infinispan -p 11222:11222 -e USER="foo" -e PASS="bar" quay.io/infinispan/server:latest
```

### Build WildFly

```bash
mvn clean install -P local
```

### Stat WildFly

```bash
export INFINISPAN_SERVER_HOST=example-infinispan
export INFINISPAN_SERVER_PORT=11222
export INFINISPAN_SERVER_TRUST_STORE_FILENAME=/distributed-sessions-infinispan/tls.crt
export INFINISPAN_SERVER_USER=foo
export INFINISPAN_SERVER_PASSWORD=bar

./target/server/bin/standalone.sh
```

Now hit http://localhost:8080/serial a few times using your browser (we need cookies), then shut down and re-start WildFly:
the serial number continues to increase because that data was stored in Infinispan and wasn't lots when WildFly stopped.

## Using Custom Certificates

### Create the key and the certificate

#### Generates a key pair into a JKS store (privatekey.pkcs12)

```shell
keytool -genkeypair -noprompt -alias self -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -dname "CN=example-infinispan.infinispan.svc" -validity 365 -keystore privatekey.pkcs12 -storepass password -storetype PKCS12
```

List the content in `privatekey.pkcs12`:
```shell
keytool -list -keystore privatekey.pkcs12 -storepass password
```

#### Exports certificate (example-infinispan.crt)
```shell
keytool -exportcert -noprompt -rfc -alias self -file example-infinispan.crt -keystore privatekey.pkcs12 -storepass password -storetype PKCS12
```

#### Export private key
```shell
openssl pkcs12 -in privatekey.pkcs12 -nocerts -nodes -out privatekey.key -passin pass:password
```

### Create a TLS Secret containing key and certificate
This one is for the Infinispan Server:
```shell
oc create secret generic example-infinispan-custom-key-and-certificate-secret --from-file=tls.crt=/tmp/tls/example-infinispan.crt --from-file=tls.key=/tmp/tls/privatekey.key 
```

oc create secret generic example-infinispan-custom-keystore-secret --from-file=keystore.p12=/tmp/tls/privatekey.pkcs12 --from-env-file /tmp/tls/stringData
```

### Create a TLS Secret containing just the certificate
This one is for WildFly:
```shell
oc create secret generic example-infinispan-custom-certificate-secret --from-file=example-infinispan.crt=/tmp/tls/example-infinispan.crt
```

### Create the Infinispan Server using these keys

/tmp/example-infinispan.yaml:
```yaml
apiVersion: infinispan.org/v1
kind: Infinispan
metadata:
  name: example-infinispan
  namespace: infinispan
spec:
  security:
    endpointEncryption:
      type: Secret
      certSecretName: example-infinispan-custom-key-and-certificate-secret
      clientCert: None
    endpointSecretName: connect-secret
  replicas: 1
```

```shell
echo 'password="password"' > /tmp/tls/stringData
echo 'alias="self"' >> /tmp/tls/stringData
```
```yaml
apiVersion: infinispan.org/v1
kind: Infinispan
metadata:
  name: example-infinispan
  namespace: infinispan
spec:
  security:
    endpointEncryption:
      type: Secret
      certSecretName: example-infinispan-custom-keystore-secret
      clientCert: None
    endpointSecretName: connect-secret
  replicas: 1
```

```bash
oc apply -f /tmp/example-infinispan.yaml
```

