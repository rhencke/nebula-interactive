package netflix.nebula.interactive

import io.netty.handler.codec.http.HttpHeaders
import io.reactivex.netty.RxNetty
import io.reactivex.netty.protocol.http.server.RequestHandler
import io.reactivex.netty.protocol.http.server.RequestHandlerWithErrorMapper
import io.reactivex.netty.protocol.http.server.file.FileErrorResponseMapper
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.tasks.TaskAction

import java.awt.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class InteractiveDependenciesTask extends DefaultTask {
    private final int PORT = 8641

    int recurseDependencies(Set artifacts, Set links, ResolvedDependency dep, int index) {
        def artifact = new Artifact(dep)
        if(artifacts.add(artifact)) {
            artifact.index = index++
            for (ResolvedDependency child : dep.children) {
                index = recurseDependencies(artifacts, links, child, index)
                links.add(new Link(artifact, new Artifact(child)))
            }
        }
        return index
    }

    ResolvedDependency projectAsResolvedDependency() {
        [
                getModuleGroup: { project.group },
                getModuleName: { project.name },
                getModuleVersion: { project.version },
                getChildren: { project.configurations.testRuntime.resolvedConfiguration.firstLevelModuleDependencies }
        ] as ResolvedDependency
    }

    @TaskAction
    def serveInteractiveContent() {
        def links = new HashSet<Link>()
        def nodes = new LinkedHashSet<Artifact>()

        recurseDependencies(nodes, links, projectAsResolvedDependency(), 0)

        def results = [
            nodes: new ArrayList(nodes),
            links: links.collect { link -> [source: link.source.index, target: nodes.find { it == link.target }.index] }
        ]

        def nodesJson = nodes.collect { it.json }.join(',')
        def linksJson = links.collect { link ->
            def targetIndex = nodes.find { it == link.target }.index
            """{"source":$link.source.index,"target":$targetIndex}"""
        }.join(',')

        def resultJson = """{"nodes":[$nodesJson],"links":[$linksJson]}"""

        def server = null
        try {
            def latch = new CountDownLatch(1)

            def staticHandler = RequestHandlerWithErrorMapper.from(
                    new ClassPathFileRequestHandler('static'),
                    new FileErrorResponseMapper())

            server = RxNetty.createHttpServer(PORT,
                new HttpRouter()
                    .get('/dependencies',
                        { request, response ->
                            response.getHeaders().set(HttpHeaders.Names.CONTENT_TYPE, 'application/json');
                            response.writeString(resultJson)
                            latch.countDown()
                            response.close()
                        } as RequestHandler
                    )
                    .noMatch(staticHandler)
            ).start()

            Desktop.getDesktop().browse(new URI("http://localhost:$PORT/index.html"))

            latch.await(60, TimeUnit.SECONDS)
        } finally {
            server?.shutdown()
        }
    }
}
