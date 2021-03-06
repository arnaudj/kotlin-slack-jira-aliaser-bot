package com.github.arnaudj.linkify.engines.jira

import com.github.arnaudj.linkify.eventdriven.commands.Command
import com.github.arnaudj.linkify.eventdriven.events.Event
import com.github.arnaudj.linkify.eventdriven.events.EventSourceData
import com.github.arnaudj.linkify.engines.jira.entities.JiraEntity
import com.github.arnaudj.linkify.engines.jira.entities.ResolveJiraCommand
import com.github.arnaudj.linkify.engines.jira.entities.JiraResolvedEvent
import com.github.arnaudj.linkify.engines.jira.entities.JiraSeenEvent
import com.github.arnaudj.linkify.spi.jira.JiraResolutionService
import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.instance
import com.google.common.eventbus.DeadEvent
import com.google.common.eventbus.EventBus
import com.google.common.eventbus.Subscribe
import com.google.common.util.concurrent.*
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.Executors


interface AppEventHandler {
    fun onJiraResolvedEvent(event: JiraResolvedEvent, kodein: Kodein)
}

interface JiraEngineThrottlingStrategy {
    fun shouldThrottle(event: JiraSeenEvent): Boolean
}

class JiraResolutionEngine(private val kodein: Kodein, workerPoolSize: Int, private val appEventHandler: AppEventHandler, private val jiraEngineThrottlingStrategy: JiraEngineThrottlingStrategy) {
    private val logger = LoggerFactory.getLogger(JiraResolutionEngine::class.java)
    private val eventBus: EventBus = EventBus()
    private val jiraService: JiraResolutionService = kodein.instance()
    private val workerPool: ListeningExecutorService

    init {
        eventBus.register(this)
        workerPool = if (workerPoolSize > 0)
            MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(workerPoolSize))
        else
            MoreExecutors.listeningDecorator(MoreExecutors.newDirectExecutorService())
    }

    fun handleMessage(message: String, source: EventSourceData) =
            arrayOf(JiraEventFactory())
                    .flatMap { factory -> factory.createFrom(message, source) }
                    .forEach { event -> postBusEvent(event) }

    @Subscribe
    fun onResolveJiraCommand(event: ResolveJiraCommand) {
        logger.debug("onResolveJiraCommand() handling ${event}")
        val future: ListenableFuture<JiraEntity>? = workerPool.submit(Callable<JiraEntity> { jiraService.resolve(event.key) })

        Futures.addCallback(future, object : FutureCallback<JiraEntity> {
            override fun onSuccess(result: JiraEntity?) {
                postBusEvent(JiraResolvedEvent(event.source, result!!))
            }

            override fun onFailure(t: Throwable?) {
                logger.error("Unable to resolve for event: $event", t)
            }
        }, workerPool)
    }

    @Subscribe
    fun onEvent(event: Event) {
        when (event) {
            is JiraSeenEvent -> if (!jiraEngineThrottlingStrategy.shouldThrottle(event))
                postBusCommand(ResolveJiraCommand(event.entity.key, event.source, kodein))
            is JiraResolvedEvent -> appEventHandler.onJiraResolvedEvent(event, kodein)
            else -> error("Unsupported event in bot: $event")
        }
    }

    @Subscribe
    fun onDeadEvent(event: DeadEvent) {
        error("Unexpected dead event: $event")
    }

    private fun postBusCommand(command: Command) {
        logger.debug("> postBusCommand: ${command}")
        eventBus.post(command)
    }

    private fun postBusEvent(event: Event) {
        logger.debug("> postBusEvent: ${event}")
        eventBus.post(event)
    }


}

