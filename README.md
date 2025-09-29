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
cat <<EOF > /tmp/identities.yaml
credentials:
- username: foo
  password: bar
EOF

oc create secret generic connect-secret --from-file=/tmp/identities.yaml
```

### Deploy Infinispan Server

```shell
cat <<EOF >
apiVersion: infinispan.org/v1
kind: Infinispan
metadata:
  name: example-infinispan
  labels:
   app: datagrid
   namespace: infinispan
spec:
  security:
    endpointSecretName: connect-secret
  replicas: 1
EOF

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
keytool -genkeypair -noprompt -alias server -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -dname "CN=example-infinispan.infinispan.svc" -validity 365 -keystore privatekey.pkcs12 -storepass 1234PIPPOBAUDO -storetype PKCS12 -ext 'san=dns:*.example-infinispan.infinispan.svc'
```

List the content in `privatekey.pkcs12`:
```shell
keytool -list -keystore privatekey.pkcs12 -storepass 1234PIPPOBAUDO
```

#### Exports certificate (example-infinispan.crt)
```shell
keytool -exportcert -noprompt -rfc -alias server -file hostname.crt -keystore privatekey.pkcs12 -storepass 1234PIPPOBAUDO -storetype PKCS12
```

### Create Secret containing key and certificate

```shell
cat <<EOF > example-infinispan-keystore-p12.yaml
apiVersion: v1
kind: Secret
metadata:
  name: example-infinispan-keystore-p12
type: Opaque
stringData:
  alias: server
  password: 1234PIPPOBAUDO
data:
  keystore.p12: $(cat privatekey.pkcs12 | base64 -w 0)
EOF

oc delete secret example-infinispan-keystore-p12                                                                            
oc apply -f example-infinispan-keystore-p12.yaml 
```

### Create Secret containing certificate
This one is for the Infinispan Server:
```shell
oc create secret generic example-infinispan-certificate-crt --from-file=example-infinispan.crt=hostname.crt
```

### Create the Infinispan Server using these keys

```shell
cat <<EOF > /tmp/identities.yaml
credentials:
- username: foo
  password: bar
EOF

oc create secret generic connect-secret --from-file=/tmp/identities.yaml

cat <<EOF > /tmp/example-infinispan.yaml
apiVersion: infinispan.org/v1
kind: Infinispan
metadata:
  name: example-infinispan
  namespace: infinispan
spec:
  security:
    endpointEncryption:
      type: Secret
      certSecretName: example-infinispan-keystore-p12
      clientCert: None
    endpointSecretName: connect-secret
  replicas: 1
EOF

oc apply -f /tmp/example-infinispan.yaml
```

### Deploy WildFly using Helm

```bash
helm install distributed-sessions-infinispan -f charts/distributed-sessions-infinispan-custom-certificate.yaml wildfly/wildfly
```

