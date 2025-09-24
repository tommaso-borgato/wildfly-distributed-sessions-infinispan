# WildFly Distributed Cache + Infinispan Server

A WildFly/JBoss EAP 8.x application which is provisioned by the `wildfly-maven-plugin` and that uses the
web-clustering layer to store session data in a distributed cache which is backed by an Infinispan Server service.

The WildFly/EAP Maven plugin is configured to build the application and trimmed server, based on required feature packs.


## Manual Setup

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
