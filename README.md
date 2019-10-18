Apache Hadoop Ozone
===

Ozone is a scalable, redundant, and distributed object store for Hadoop. Apart from scaling to billions of objects of varying sizes, Ozone can function effectively in containerized environments such as Kubernetes and YARN.


 * MULTI-PROTOCOL SUPPORT: Ozone supports different protocols like S3 and Hadoop File System APIs.
 * SCALABLE: Ozone is designed to scale to tens of billions of files and blocks and, in the future, even more.
 * CONSISTENT: Ozone is a strongly consistent object store. This consistency is achieved by using protocols like RAFT.
 * CLOUD-NATIVE: Ozone is designed to work well in containerized environments like YARN and Kubernetes.
 * SECURE: Ozone integrates with Kerberos infrastructure for access control and supports TDE and on-wire encryption.
 * HIGHLY AVAILABLE: Ozone is a fully replicated system that is designed to survive multiple failures.

## Documentation

The latest documentation is generated together with the releases and hosted on the apache side.

Please check [the documentation page](https://hadoop.apache.org/ozone/docs/) for more information.

## Contact

Ozone is part of the [Apache Hadoop](https://hadoop.apache.org) project.

 * Ozone [web page](https://hadoop.apache.org/ozone/) is available from the Hadoop site
 * Mailing lists
     * For dev questions use: [hdfs-dev@hadoop.apache.org](https://lists.apache.org/list.html?hdfs-dev@hadoop.apache.org)
     * For user questions use: [user@hadoop.apache.org](https://lists.apache.org/list.html?user@hadoop.apache.org)
 * Chat: You can find the #ozone channel on the official ASF slack. Invite link is [here](http://s.apache.org/slack-invite).
 * There are Open [Weekly calls](https://cwiki.apache.org/confluence/display/HADOOP/Ozone+Community+Calls) where you can ask anything about Ozone.
     * Past meeting notes are also available from the wiki.


## Download

Latest release artifacts (source release and binary packages) are available from the Ozone web page:

https://hadoop.apache.org/ozone/downloads/

## Quick start

### Run Ozone from published Docker image

The easiest way to start a cluster with docker is:

```
docker run -P 9878 apache/ozone
```

And you can use AWS S3 cli:

```
aws s3api --endpoint http://localhost:9878/ create-bucket --bucket=wordcount
aws s3 --endpoint http://localhost:9878 cp --storage-class REDUCED_REDUNDANCY  /tmp/testfile  s3://wordcount/testfile
```

### Run Ozone from released artifact

If you need more relistic cluster, you ca [download](https://hadoop.apache.org/ozone/downloads/) the last (binary) release package, and start a cluster with the help of docker-compose:

After untar the binary:

```
cd compose/ozone
docker-compose up -d --scale datanode=3
```

The `compose` folder contains different set of configured clusters (secure, HA, mapreduce example), you can check the different subfolders for more examples.

### Run on Kubernetes

Ozone is first class citizen of the Cloud-Native environments. The binary package contains multiple set of K8s resource files to show how it can be deployed.

## Build from source

Ozone can be built with [Apache Maven](https://maven.apache.org):

```
mvn clean install -DskipTests
```

And can be started with the help of Docker:

```
cd hadoop-ozone/dist/target/ozone-*/compose/
docker-compose up -d scale datanode=3
```
For more information, you can check the [Contribution guideline](./CONTRIBUTION.md)

## Contribute

All contributions are welcome.

 1. Please open a [Jira](https://issues.apache.org/) issue
 2. And create a pull request

For more information, you can check the [Contribution guideline](./CONTRIBUTION.md)

## License

The Apache Hadoop Ozone  project is licensed under the Apache 2.0 License. See the [LICENSE](./LICENSE.txt) file for details.
