# Maven Artifact 复制工具
                     
将包从一个Maven库复制到另一个Maven库中，用于Maven库迁移。


目录结构
```
maven-artifacts-copier-1.0.0-jar-with-dependencies.jar
conf.yaml
```

配置文件 ```conf.yaml```
```yaml
source:
  url:

target:
  url:
  username:
  password:

artifact:
  - GroupId:Artifact:[0,)
```

命令行
```shell
java -jar maven-artifacts-copier-1.0.0-jar-with-dependencies.jar
```