package kamon.instrumentation.akka

import akka.actor.SupervisorStrategy.{ Escalate, Restart, Resume, Stop }
import akka.actor._
import akka.testkit.ImplicitSender
import com.typesafe.config.ConfigFactory
import kamon.testkit.BaseKamonSpec
import kamon.trace.{ EmptyTraceContext, TraceContext }
import org.scalatest.WordSpecLike

import scala.concurrent.duration._
import scala.util.control.NonFatal

class ActorSystemMessageInstrumentationSpec extends BaseKamonSpec("actor-system-message-instrumentation-spec") with WordSpecLike with ImplicitSender {
  override lazy val config =
    ConfigFactory.parseString(
      """
        |akka {
        |  loglevel = OFF
        |}
      """.stripMargin)

  implicit lazy val executionContext = system.dispatcher

  "the system message passing instrumentation" should {
    "keep the TraceContext while processing the Create message in top level actors" in {
      val testTraceContext = TraceContext.withContext(newContext("creating-top-level-actor")) {
        system.actorOf(Props(new Actor {
          testActor ! TraceContext.currentContext
          def receive: Actor.Receive = { case any ⇒ }
        }))

        TraceContext.currentContext
      }

      expectMsg(testTraceContext)
    }

    "keep the TraceContext while processing the Create message in non top level actors" in {
      val testTraceContext = TraceContext.withContext(newContext("creating-non-top-level-actor")) {
        system.actorOf(Props(new Actor {
          def receive: Actor.Receive = {
            case any ⇒
              context.actorOf(Props(new Actor {
                testActor ! TraceContext.currentContext
                def receive: Actor.Receive = { case any ⇒ }
              }))
          }
        })) ! "any"

        TraceContext.currentContext
      }

      expectMsg(testTraceContext)
    }

    "keep the TraceContext in the supervision cycle" when {
      "the actor is resumed" in {
        val supervisor = supervisorWithDirective(Resume)

        val testTraceContext = TraceContext.withContext(newContext("fail-and-resume")) {
          supervisor ! "fail"
          TraceContext.currentContext
        }

        expectMsg(testTraceContext) // From the parent executing the supervision strategy

        // Ensure we didn't tie the actor with the context
        supervisor ! "context"
        expectMsg(EmptyTraceContext)
      }

      "the actor is restarted" in {
        val supervisor = supervisorWithDirective(Restart, sendPreRestart = true, sendPostRestart = true)

        val testTraceContext = TraceContext.withContext(newContext("fail-and-restart")) {
          supervisor ! "fail"
          TraceContext.currentContext
        }

        expectMsg(testTraceContext) // From the parent executing the supervision strategy
        expectMsg(testTraceContext) // From the preRestart hook
        expectMsg(testTraceContext) // From the postRestart hook

        // Ensure we didn't tie the actor with the context
        supervisor ! "context"
        expectMsg(EmptyTraceContext)
      }

      "the actor is stopped" in {
        val supervisor = supervisorWithDirective(Stop, sendPostStop = true)

        val testTraceContext = TraceContext.withContext(newContext("fail-and-stop")) {
          supervisor ! "fail"
          TraceContext.currentContext
        }

        expectMsg(testTraceContext) // From the parent executing the supervision strategy
        expectMsg(testTraceContext) // From the postStop hook
        expectNoMsg(1 second)
      }

      "the failure is escalated" in {
        val supervisor = supervisorWithDirective(Escalate, sendPostStop = true)

        val testTraceContext = TraceContext.withContext(newContext("fail-and-escalate")) {
          supervisor ! "fail"
          TraceContext.currentContext
        }

        expectMsg(testTraceContext) // From the parent executing the supervision strategy
        expectMsg(testTraceContext) // From the grandparent executing the supervision strategy
        expectMsg(testTraceContext) // From the postStop hook in the child
        expectMsg(testTraceContext) // From the postStop hook in the parent
        expectNoMsg(1 second)
      }
    }
  }

  def supervisorWithDirective(directive: SupervisorStrategy.Directive, sendPreRestart: Boolean = false, sendPostRestart: Boolean = false,
    sendPostStop: Boolean = false, sendPreStart: Boolean = false): ActorRef = {
    class GrandParent extends Actor {
      val child = context.actorOf(Props(new Parent))

      override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
        case NonFatal(throwable) ⇒ testActor ! TraceContext.currentContext; Stop
      }

      def receive = {
        case any ⇒ child forward any
      }
    }

    class Parent extends Actor {
      val child = context.actorOf(Props(new Child))

      override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
        case NonFatal(throwable) ⇒ testActor ! TraceContext.currentContext; directive
      }

      def receive: Actor.Receive = {
        case any ⇒ child forward any
      }

      override def postStop(): Unit = {
        if (sendPostStop) testActor ! TraceContext.currentContext
        super.postStop()
      }
    }

    class Child extends Actor {
      def receive = {
        case "fail"    ⇒ throw new ArithmeticException("Division by zero.")
        case "context" ⇒ sender ! TraceContext.currentContext
      }

      override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
        if (sendPreRestart) testActor ! TraceContext.currentContext
        super.preRestart(reason, message)
      }

      override def postRestart(reason: Throwable): Unit = {
        if (sendPostRestart) testActor ! TraceContext.currentContext
        super.postRestart(reason)
      }

      override def postStop(): Unit = {
        if (sendPostStop) testActor ! TraceContext.currentContext
        super.postStop()
      }

      override def preStart(): Unit = {
        if (sendPreStart) testActor ! TraceContext.currentContext
        super.preStart()
      }
    }

    system.actorOf(Props(new GrandParent))
  }
}