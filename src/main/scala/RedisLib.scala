import scala.concurrent. { Await, Future, Promise}
import scala.concurrent.duration._
import scala.util. {Left, Right}
import akka.util.Timeout
import akka.pattern.{ ask, pipe }
import akka.actor._
import spray.util._
import spray.io._

object RedisClient {
  case class Get(key: String)
  case class Set(key: String, Value: String)
  class KeyNotFoundException extends Exception
  case class SetConnection(handle: Connection)
  def apply(host: String, port: Int, ioBridge: ActorRef) (implicit system: ActorSystem) = {
    val client = system.actorOf(Props(new RedisClient(ioBridge)), "redis-client")
    implicit val timeout = Timeout(5 seconds)
    // Block here.
    val IOClient.Connected(handle) = Await.result(client.ask(IOClient.Connect(host, port)), timeout.duration)
    client ! SetConnection(handle)
    client
  }
}

class RedisClient(ioBridge1: ActorRef) extends IOClient(ioBridge1) {
  // Our queue to maintain the order of requests.
  val promiseQueue = new scala.collection.mutable.Queue[Promise[_]]

  // We will use this connection to store our connection to the Redis server.
  var connection: Connection = _

  override def receive = myReceive orElse super.receive

  def myReceive: Receive = {
    case RedisClient.SetConnection(connection: Connection) => this.connection = connection
    case RedisClient.Get(key) => {
      val readCommand = "*2\r\n" +
                       "$3\r\n" +
                       "GET\r\n" +
                       "$" + key.length + "\r\n" +
                       key + "\r\n"
      // Send the command bytes to Redis and queue a promise with the type of the expected result.
      sendRedisCommand(readCommand, Promise[String])
    }
    case RedisClient.Set(key, value) => {
      val writeCommand = "*3\r\n" +
                          "$3\r\n" +
                          "SET\r\n" +
                          "$" + key.length + "\r\n" +
                          key + "\r\n" +
                          "$" + value.length + "\r\n" +
                          value + "\r\n"
      sendRedisCommand(writeCommand, Promise[Boolean])
    }

    // NEW STUFF HERE ......
    case IOClient.Received(handle, buffer) => {
      val z = buffer.drainToString
      // We first split the response to get the different "lines".
      val responseArray = z.split("\r\n")
      // We zip with index so that we can move to certain indices of the response Array.
      responseArray.zipWithIndex.foreach { case (response, index) =>
        if (response startsWith "+") {
          // Must be a response to a SET request. Check if the string after "+" is an "OK".
          val setAnswer = response.substring(1, response.length)
          val nextPromise = promiseQueue.dequeue.asInstanceOf[Promise[Boolean]]
          nextPromise success (if (setAnswer == "OK") true else false)
        } else if (response startsWith "$") {
          // Must be a response to a GET request. The next line contains the result.
          val nextPromise = promiseQueue.dequeue.asInstanceOf[Promise[String]]
          if (response endsWith "-1") {
            nextPromise failure (new RedisClient.KeyNotFoundException)
          } else {
            nextPromise success responseArray(index + 1)
          }
        }
      }
    }
    case IOClient.Closed(_, reason) => println("Conenection closed ", reason)
  }
  def sendRedisCommand(command: String, resultPromise: Promise[_]) {
    // Send the bytes to the Redis Server.
    connection.ioBridge ! IOBridge.Send(connection, BufferBuilder(command).toByteBuffer)
    // Add the promise to our queue and pipe the future to the caller.
    promiseQueue += resultPromise
    val f = resultPromise.future
    pipe(f) to sender
  }
}

object Main extends App {
  // We need an ActorSystem to host our application in.
  implicit val system = ActorSystem()
  // Create and start an IOBridge.
  val ioBridge = IOExtension(system).ioBridge()
  // The futures returned by the ask pattern will time out based on this implicit timeout.
  implicit val timeout = Timeout(5 seconds)
 // Our Redis client.
  val client = RedisClient("127.0.0.1", 6379, ioBridge)

  // Get a list of keys and values to set in Redis.
  // keys = hello1, hello2, ...
  // values = world1, world2, ...
  val numKeys = 3
  val (keys, values) = (1 to numKeys map { num => ("hello" + num, "world" + num) }).unzip

  // Set the keys in Redis. We store the Futures with the keys and values to print them later.
  val writeResults = keys zip values map { case (key, value) =>
    (key, value, (client ? RedisClient.Set(key, value)).mapTo[Boolean])
    }
  writeResults foreach { case (key, value, result) =>
    result onSuccess {
      case someBoolean if someBoolean == true => println("Set " + key + " to value " + value)
      case _ => println("Failed to set key " + key + " to value " + value)
    }
    result onFailure {
      case t => println("Failed to set key " + key + " to value " + value + " : " + t)
    }
  }

  // Get the keys from Redis. Store the keys with the Futures to print them later.
  val readResults = keys map { key => (key, client.ask(RedisClient.Get(key)).mapTo[String]) }
  readResults zip values foreach { case ((key, result), expectedValue) =>
    result.onSuccess {
      case resultString =>
        println("Got a result for " + key + ": "+ resultString)
        assert(resultString == expectedValue)
    }
    result.onFailure {
      case t => println("Got some exception " + t)
    }
  }
}