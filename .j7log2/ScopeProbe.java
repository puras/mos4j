import java.nio.file.Path;

import net.hegq.mos4j.sandbox.LocalRunner;
import net.hegq.mos4j.sandbox.PathScope;
import net.hegq.mos4j.sandbox.PathScopes;
import net.hegq.mos4j.sandbox.RunRequest;
import net.hegq.mos4j.sandbox.RunResult;

public class ScopeProbe {
    public static void main(String[] args) throws Exception {
        Path work = Path.of("mos4j-sandbox/target/test-tmp/probe").toAbsolutePath();
        java.nio.file.Files.createDirectories(work);
        System.out.println("supported=" + PathScopes.supported());
        PathScope scope = PathScopes.derive(work);
        System.out.println("description=" + scope.description());
        LocalRunner runner = LocalRunner.builder(work).pathScope(scope).build();
        RunResult ok = runner.run(new RunRequest("echo hi > a.txt; cat a.txt", null, null));
        System.out.println("inside: started=" + ok.started() + " exit=" + ok.exitCode()
                + " out=" + ok.stdout().trim() + " err=" + ok.stderr().trim().replace('\n', '|'));
        RunResult outside = runner.run(new RunRequest("echo hi > " + work.getParent().resolve("escape.txt"), null, null));
        System.out.println("outside: started=" + outside.started() + " exit=" + outside.exitCode()
                + " err=" + outside.stderr().trim().replace('\n', '|'));
        System.out.println("escape exists=" + java.nio.file.Files.exists(work.getParent().resolve("escape.txt")));
    }
}