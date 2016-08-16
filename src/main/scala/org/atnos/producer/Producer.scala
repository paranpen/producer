package org.atnos.producer

import cats._, data._
import cats.implicits._
import org.atnos.eff._
import org.atnos.eff.all._
import org.atnos.eff.syntax.all._
import Producer._

sealed trait Stream[R, A]
case class Done[R, A]() extends Stream[R, A]
case class One[R, A](a: A) extends Stream[R, A]
case class More[R, A](as: List[A], next: Producer[R, A]) extends Stream[R, A]

case class Producer[R :_eval, A](run: Eff[R, Stream[R, A]]) {

  def flatMap[B](f: A => Producer[R, B]): Producer[R, B] =
    cata[R, A, B](this)(
      done[R, B],
      (a: A) => f(a),
      (as: List[A], next: Producer[R, A]) => as.traverse(f).flatMap(emit[R, B]) append next.flatMap(f))

  def map[B](f: A => B): Producer[R, B] =
    flatMap(a => one(f(a)))

  def append(other: =>Producer[R, A]): Producer[R, A] =
    Producer(run flatMap {
      case Done() => other.run
      case One(a) => delay(More(List(a), other))
      case More(as, next) => delay(More(as, next append other))
    })

  def zip[B](other: Producer[R, B]): Producer[R, (A, B)] =
    Producer(run flatMap {
      case Done() => done.run
      case One(a) =>
        other.run flatMap {
          case Done() => done.run
          case One(b) => one((a, b)).run

          case More(bs, next) =>
            bs.headOption.map(b => one[R, (A, B)]((a, b))).getOrElse(done).run
        }

      case More(Nil, next) => done.run

      case More(as, nexta) =>
        other.run flatMap {
          case Done() => done.run
          case One(b) => as.headOption.map(a => one[R, (A, B)]((a, b))).getOrElse(done).run

          case More(bs, nextb) =>
            if (as.size == bs.size)
              emit(as zip bs).run
            else if (as.size < bs.size)
              (emit(as zip bs) append (nexta zip (emit(bs.drop(as.size)) append nextb))).run
            else
              (emit(as zip bs) append ((emit(as.drop(bs.size)) append nexta) zip nextb)).run
        }
    })
}


object Producer extends Producers {
  implicit def FoldableProducer: Foldable[Producer[NoFx, ?]] = new Foldable[Producer[NoFx, ?]] {
    def foldLeft[A, B](fa: Producer[NoFx, A], b: B)(f: (B, A) => B): B = {
      var s = b
      fa.run.run match {
        case Done() => Eff.pure(())
        case One(a) => s = f(s, a); Eff.pure(())
        case More(as, next) => s = as.foldLeft(s)(f); s = foldLeft(next, s)(f); Eff.pure(())
      }
      s
    }

    def foldRight[A, B](fa: Producer[NoFx, A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] = {
      var s = lb
      fa.run.run match {
        case Done() => Eff.pure(())
        case One(a) => s = s >> f(a, s); Eff.pure(())
        case More(as, next) => s >> as.foldRight(s)(f) >> foldRight(next, s)(f); Eff.pure(())
      }
      s
    }
  }

  implicit def ProducerMonad[R :_eval]: Monad[Producer[R, ?]] = new Monad[Producer[R, ?]] {
    def flatMap[A, B](fa: Producer[R, A])(f: A => Producer[R, B]): Producer[R, B] =
      fa.flatMap(f)

    def pure[A](a: A): Producer[R, A] =
      one(a)
  }

}

trait Producers {
  def done[R :_eval, A]: Producer[R, A] =
    Producer[R, A](delay(Done()))

  def one[R :_eval, A](a: A): Producer[R, A] =
    Producer[R, A](delay(One(a)))

  def oneOrMore[R :_eval, A](a: A, as: List[A]): Producer[R, A] =
    Producer[R, A](delay(More(a +: as, done)))

  def repeat[R :_eval, A](p: Producer[R, A]): Producer[R, A] =
    Producer(p.run flatMap {
      case Done() => delay(Done())
      case One(a) => delay(More(List(a), repeat(p)))
      case More(as, next) => delay(More(as, next append repeat(p)))
    })

  def repeatValue[R :_eval, A](a: A): Producer[R, A] =
    Producer(delay(More(List(a), repeatValue(a))))

  def repeatEval[R :_eval, A](e: Eff[R, A]): Producer[R, A] =
    emitEff(e.map(List(_))) append repeatEval(e)

  def fill[R :_eval, A](n: Int)(p: Producer[R, A]): Producer[R, A] =
    if (n <= 0) done[R, A]
    else p append fill(n - 1)(p)

  def emit[R :_eval, A](elements: List[A]): Producer[R, A] =
    elements match {
      case Nil      => done[R, A]
      case a :: Nil => one[R, A](a)
      case a :: as  => oneOrMore(a, as)
    }

  def eval[R :_eval, A](a: Eff[R, A]): Producer[R, A] =
    Producer(a.map(One(_)))

  def emitEff[R :_eval, A](elements: Eff[R, List[A]]): Producer[R, A] =
    Producer(elements flatMap {
      case Nil      => done[R, A].run
      case a :: Nil => one(a).run
      case a :: as  => oneOrMore(a, as).run
    })

  def fold[R :_eval, A, B, S](producer: Producer[R, A])(start: Eff[R, S], f: (S, A) => S, end: S => Eff[R, B]): Eff[R, B] =
    start.flatMap { init =>
      def go(p: Producer[R, A], s: S): Eff[R, S] =
        p.run flatMap {
          case Done() => delay[R, S](s)
          case One(a) => delay[R, S](f(s, a))
          case More(as, next) =>
            val newS = as.foldLeft(s)(f)
            go(next, newS)
        }

      go(producer, init)
    } flatMap end

  def observe[R :_eval, A, S](producer: Producer[R, A])(start: Eff[R, S], f: (S, A) => S, end: S => Eff[R, Unit]): Producer[R, A] =
    Producer[R, A](start flatMap { init =>
      def go(p: Producer[R, A], s: S): Producer[R, A] =
        Producer[R, A] {
          p.run flatMap {
            case Done() => end(s) >> done[R, A].run
            case One(a) => end(s) >> one[R, A](a).run
            case More(as, next) =>
              val newS = as.foldLeft(s)(f)
              (emit(as) append go(next, newS)).run
          }
        }

      go(producer, init).run
    })

  def runLast[R :_eval, A](producer: Producer[R, A]): Eff[R, Option[A]] =
    producer.run flatMap {
      case Done() => delay[R, Option[A]](None)
      case One(a) => delay[R, Option[A]](Option(a))
      case More(as, next) => runLast(next).map(_.orElse(as.lastOption))
    }

  def runList[R :_eval, A](producer: Producer[R, A]): Eff[R, List[A]] =
    producer.fold(delay(Vector[A]()), (vs: Vector[A], a: A) => vs :+ a, (vs:Vector[A]) => delay(vs.toList))

  def collect[R :_eval, A](producer: Producer[R, A])(implicit m: Member[Writer[A, ?], R]): Eff[R, Unit] =
    producer.run flatMap {
      case Done() => delay(())
      case One(a) => tell(a)
      case More(as, next) => as.traverse(tell[R, A]) >> collect(next)
    }

  def into[R, U, A](producer: Producer[R, A])(implicit intoPoly: IntoPoly[R, U], eval: Eval |= U): Producer[U, A] =
    Producer(producer.run.into[U] flatMap {
      case Done() => done[U, A].run
      case One(a) => one[U, A](a).run
      case More(as, next) => delay(More[U, A](as, into(next)))
    })

  def empty[R :_eval, A]: Producer[R, A] =
    done

  def pipe[R, A, B](p: Producer[R, A], t: Transducer[R, A, B]): Producer[R, B] =
    t(p)

  def filter[R :_eval, A](producer: Producer[R, A])(f: A => Boolean): Producer[R, A] =
    Producer(producer.run flatMap {
      case Done() => done.run
      case One(a) => (if (f(a)) one[R, A](a) else done[R, A]).run
      case More(as, next) =>
        as filter f match {
          case Nil => next.filter(f).run
          case a :: rest => (oneOrMore(a, rest) append next.filter(f)).run
        }
    })

  def flatten[R :_eval, A](producer: Producer[R, Producer[R, A]]): Producer[R, A] =
    Producer(producer.run flatMap {
      case Done() => done.run
      case One(p) => p.run
      case More(ps, next) => (flattenProducers(ps) append flatten(next)).run
    })

  def flattenProducers[R :_eval, A](producers: List[Producer[R, A]]): Producer[R, A] =
    producers match {
      case Nil => done
      case p :: rest => p append flattenProducers(rest)
    }

  /** accumulate chunks of size n inside More nodes */
  def chunk[R :_eval, A](size: Int)(producer: Producer[R, A]): Producer[R, A] = {
    def go(p: Producer[R, A], elements: Vector[A]): Producer[R, A] =
      Producer[R, A](
        p.run flatMap {
          case Done() => emit[R, A](elements.toList).run
          case One(a) => emit[R, A]((elements :+ a).toList).run

          case More(as, next) =>
            val es = elements ++ as
            if (es.size == size) (emit[R, A](es.toList) append go(next, Vector.empty)).run
            else                 go(next, es).run
        })

    go(producer, Vector.empty)
  }

  def sliding[R :_eval, A](size: Int)(producer: Producer[R, A]): Producer[R, List[A]] = {

    def go(p: Producer[R, A], elements: Vector[A]): Producer[R, List[A]] =
      Producer[R, List[A]](
      peek(p).flatMap {
        case (Some(a), as) =>
          val es = elements :+ a
          if (es.size == size) (one(es.toList) append go(as, Vector.empty)).run
          else                 go(as, es).run

        case (None, _) =>
          one(elements.toList).run
      })

    go(producer, Vector.empty)
  }

  def peek[R :_eval, A](producer: Producer[R, A]): Eff[R, (Option[A], Producer[R, A])] =
    producer.run map {
      case Done() => (None, done[R, A])
      case One(a) => (Option(a), done[R, A])
      case More(as, next) => (as.headOption, emit(as.tail) append next)
    }

  def flattenList[R :_eval, A](producer: Producer[R, List[A]]): Producer[R, A] =
    producer.flatMap(emit[R, A])

  def sequence[R :_eval, F[_], A](n: Int)(producer: Producer[R, Eff[R, A]])(implicit f: F |= R) =
    sliding(n)(producer).flatMap { actions => emitEff(Eff.sequenceA(actions)) }

  private[producer] def cata[R :_eval, A, B](producer: Producer[R, A])(onDone: Producer[R, B], onOne: A => Producer[R, B], onMore: (List[A], Producer[R, A]) => Producer[R, B]): Producer[R, B] =
    Producer[R, B](producer.run.flatMap {
      case Done() => onDone.run
      case One(a) => onOne(a).run
      case More(as, next) => onMore(as, next).run
    })

}


trait Transducers {
  def filter[R :_eval, A, B](f: A => Boolean): Transducer[R, A, A] =
    (p: Producer[R, A]) => Producer.filter(p)(f)

  def receive[R :_eval, A, B](f: A => Producer[R, B]): Transducer[R, A, B] =
    (p: Producer[R, A]) => p.flatMap(f)

  def transducer[R :_eval, A, B](f: A => B): Transducer[R, A, B] =
    (p: Producer[R, A]) => p.map(f)

  def receiveOr[R :_eval, A, B](f: A => Producer[R, B])(or: =>Producer[R, B]): Transducer[R, A, B] =
    cata_[R, A, B](
      or,
      (a: A) => f(a),
      (as: List[A], next: Producer[R, A]) => as.headOption.map(f).getOrElse(or))

  def receiveOption[R :_eval, A, B]: Transducer[R, A, Option[A]] =
    receiveOr[R, A, Option[A]]((a: A) => one(Option(a)))(one(None))

  def drop[R :_eval, A](n: Int): Transducer[R, A, A] =
    cata_[R, A, A](
      done[R, A],
      (a: A) => if (n <= 0) one(a) else done,
      (as: List[A], next: Producer[R, A]) =>
        if (n < as.size) emit(as.drop(n)) append next
        else next |> drop(n - as.size))

  def dropRight[R :_eval, A](n: Int): Transducer[R, A, A] =
    (producer: Producer[R, A]) => {
       def go(p: Producer[R, A], elements: Vector[A]): Producer[R, A] =
         Producer(peek(p).flatMap {
           case (Some(a), as) =>
             val es = elements :+ a
             if (es.size >= n) (emit(es.toList) append go(as, Vector.empty[A])).run
             else go(as, es).run

           case (None, _) =>
             if (elements.size <= n) done.run
             else emit(elements.toList).run

         })
      go(producer, Vector.empty[A])
    }

  def take[R :_eval, A](n: Int): Transducer[R, A, A] =
    cata_[R, A, A](
      done[R, A],
      (a: A) => if (n <= 0) done else one(a),
      (as: List[A], next: Producer[R, A]) =>
        if (n <= as.size) emit(as.take(n))
        else emit(as) append (next |> take(n - as.size)))

  def takeWhile[R :_eval, A](f: A => Boolean): Transducer[R, A, A] =
    cata_[R, A, A](
      done[R, A],
      (a: A) => if (f(a)) one(a) else done,
      (as: List[A], next: Producer[R, A]) =>
        as.takeWhile(f) match {
          case Nil => done
          case some => emit(some) append next.takeWhile(f)
        })

  def zipWithPrevious[R :_eval, A]: Transducer[R, A, (Option[A], A)] =
    (producer: Producer[R, A]) => {
      def go(p: Producer[R, A], previous: Option[A]): Producer[R, (Option[A], A)] =
        Producer(peek(p) flatMap {
          case (Some(a), as) => (one((previous, a)) append go(as, Option(a))).run
          case (None, _)     => done.run
        })

      go(producer, None)
    }

  def zipWithNext[R :_eval, A]: Transducer[R, A, (A, Option[A])] =
    (producer: Producer[R, A]) => {
      def go(p: Producer[R, A], previous: A): Producer[R, (A, Option[A])] =
        Producer(peek(p) flatMap {
          case (Some(a), as) => (one((previous, Option(a))) append go(as, a)).run
          case (None, _)     => one[R, (A, Option[A])]((previous, None)).run
        })
      Producer(peek(producer) flatMap {
        case (Some(a), as) => go(as, a).run
        case (None, _) => done.run
      })
    }

  def zipWithPreviousAndNext[R :_eval, A]: Transducer[R, A, (Option[A], A, Option[A])] =
    (p: Producer[R, A]) =>
      ((p |> zipWithPrevious) zip (p |> zipWithNext)).map { case ((prev, a), (_, next)) => (prev, a, next) }


  def zipWithIndex[R :_eval, A]: Transducer[R, A, (A, Int)] =
    zipWithState[R, A, Int](0)((_, n: Int) => n + 1)

  def zipWithState[R :_eval, A, B](b: B)(f: (A, B) => B): Transducer[R, A, (A, B)] =
    (producer: Producer[R, A]) => {
      def go(p: Producer[R, A], state: B): Producer[R, (A, B)] =
        Producer[R, (A, B)] {
          producer.run flatMap {
            case Done() => done.run
            case One(a) => one((a, state)).run

            case More(as, next) =>
              val zipped: Vector[(A, B)] =
                as match {
                  case Nil => Vector.empty
                  case a :: rest => rest.foldLeft((Vector((a, state)), state)) { case ((ls, s), cur) =>
                    val newState = f(cur, s)
                    (ls :+ ((cur, newState)), newState)
                  }._1
                }

              (emit(zipped.toList) append zipWithState(b)(f).apply(next)).run

          }
        }
        go(producer, b)
    }

  def intersperse[R :_eval, A](in: A): Transducer[R, A, A] =
    (producer: Producer[R, A]) =>
      Producer[R, A](
        producer.run flatMap {
          case Done() => done.run
          case One(a) => one(a).run
          case More(Nil, next) => intersperse(in).apply(next).run
          case More(as, next) =>
            val interspersed = as.init.foldRight(as.lastOption.toList)(_ +: in +: _)

            (emit(interspersed) append
              Producer(next.run flatMap {
                case Done() => done[R, A].run
                case _ =>      (one[R, A](in) append intersperse(in).apply(next)).run
            })).run
        })


  private def cata_[R :_eval, A, B](onDone: Producer[R, B], onOne: A => Producer[R, B], onMore: (List[A], Producer[R, A]) => Producer[R, B]): Transducer[R, A, B] =
    (producer: Producer[R, A]) => cata(producer)(onDone, onOne, onMore)
}

object Transducers extends Transducers
