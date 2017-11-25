package cc.whohow.maven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.util.repository.AuthenticationBuilder;

import java.io.File;
import java.util.stream.StreamSupport;

public class Main {
    public static void main(String[] args) throws Exception {
        JsonNode conf = new ObjectMapper(new YAMLFactory()).readTree(new File("conf.yaml"));

        RemoteRepository source = newRemoteRepository("source", conf.path("source"));
        RemoteRepository target = newRemoteRepository("target", conf.path("target"));

        MavenArtifactCopier mavenArtifactCopier = new MavenArtifactCopier(source, target);
        StreamSupport.stream(conf.path("artifact").spliterator(), false)
                .map(JsonNode::textValue)
                .forEach(mavenArtifactCopier::copy);
    }

    private static RemoteRepository newRemoteRepository(String id, JsonNode conf) {
        JsonNode type = conf.path("type");
        JsonNode url = conf.path("url");
        JsonNode username = conf.path("username");
        JsonNode password = conf.path("password");

        RemoteRepository.Builder builder = new RemoteRepository.Builder(
                id, type.asText("default"), url.textValue());

        if (username.isMissingNode() && password.isMissingNode()) {
            return builder.build();
        }

        Authentication authentication = new AuthenticationBuilder()
                .addUsername(username.textValue())
                .addPassword(password.textValue()).build();
        return builder.setAuthentication(authentication).build();
    }
}
