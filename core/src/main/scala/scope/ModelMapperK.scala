package scope

import cats.{~>, Applicative, Contravariant, FlatMap, Functor, Id}
import cats.arrow.FunctionK
import cats.data.Kleisli

import scala.annotation.{implicitAmbiguous, implicitNotFound, unused}

@implicitNotFound(msg = "Cannot find a mapper for the scope ${S}")
@implicitAmbiguous(msg = "Multiple mapper for the same type ${M2} and same scope ${S}")
class ModelMapperK[F[_], S <: Scope, A, B](private[scope] val mapper: Kleisli[F, A, B]) {

  def apply(a: A)(implicit @unused scopeContext: TypedScopeContext[S]): F[B] = mapper(a)

  def mapScope[S2 <: Scope]: ModelMapperK[F, S2, A, B] =
    this.asInstanceOf[ModelMapperK[F, S2, A, B]]

  def contramap[U](f: U => A): ModelMapperK[F, S, U, B] =
    ModelMapperK.scoped[S](mapper.local(f))

  def map[C](f: B => C)(implicit F: Functor[F]): ModelMapperK[F, S, A, C] =
    ModelMapperK.scoped[S](mapper.map(f))

  def lift[K[_]: Applicative](implicit env: F[Any] =:= Id[Any]): ModelMapperK[K, S, A, B] =
    mapK[K](new FunctionK[F, K] {
      def apply[U](fa: F[U]): K[U] =
        Applicative[K].pure(fa.asInstanceOf[U])
    })

  def mapK[K[_]](f: F ~> K): ModelMapperK[K, S, A, B] =
    ModelMapperK.scoped[S](mapper.mapK(f))

  def flatMap[C, AA <: A](f: B => ModelMapperK[F, S, AA, C])(implicit
    F: FlatMap[F]
  ): ModelMapperK[F, S, AA, C] =
    ModelMapperK.scoped[S](mapper.flatMap[C, AA](b => f(b).mapper))

  def flatMapF[C](f: B => F[C])(implicit F: FlatMap[F]): ModelMapperK[F, S, A, C] =
    ModelMapperK.scoped[S](mapper.flatMapF(f))

  def compile: ModelMapper[S, A, F[B]] =
    ModelMapper.scoped[S](mapper.run)
}
object ModelMapperK extends ModelMapperKInstances {

  private val builderK: ModelMapperK.BuilderK[Scope] = new ModelMapperK.BuilderK[Scope]

  def scoped[S <: Scope]: ModelMapperK.BuilderK[S] =
    builderK.asInstanceOf[ModelMapperK.BuilderK[S]]

  class BuilderK[S <: Scope] private[ModelMapperK] () {

    def summon[F[_], A, B](implicit m: ModelMapperK[F, S, A, B]): ModelMapperK[F, S, A, B] = m

    def apply[F[_], A, B](k: Kleisli[F, A, B]): ModelMapperK[F, S, A, B] =
      new ModelMapperK[F, S, A, B](k)

    def apply[F[_], A, B](f: A => F[B]): ModelMapperK[F, S, A, B] =
      apply(Kleisli(f))

    def pure[F[_]: Applicative, A, B](b: B): ModelMapperK[F, S, A, B] =
      lift(_ => b)

    def id[F[_]: Applicative, A]: ModelMapperK[F, S, A, A] =
      lift(identity)

    def lift[F[_]: Applicative, A, B](f: A => B): ModelMapperK[F, S, A, B] =
      apply(f.andThen(Applicative[F].pure(_)))
  }
}

trait ModelMapperKInstances {

  implicit def squashModelMapperK[F[_], S <: Scope, A, B](implicit
    m: ModelMapperK[F, S, A, B]
  ): ModelMapper[S, A, F[B]] = m.compile

  implicit def liftPureModelMapper[F[_]: Applicative, S <: Scope, A, B](implicit
    m: ModelMapper[S, A, B]
  ): ModelMapperK[F, S, A, B] =
    m.mapK[F](new FunctionK[Id, F] {
      override def apply[AA](fa: Id[AA]): F[AA] = Applicative[F].pure(fa)
    })

  implicit def contravariantForModelMapperK[F[_], S <: Scope, AA, BB]
    : Contravariant[ModelMapperK[F, S, *, BB]] = new Contravariant[ModelMapperK[F, S, *, BB]] {
    override def contramap[A, B](fa: ModelMapperK[F, S, A, BB])(
      f: B => A
    ): ModelMapperK[F, S, B, BB] = fa.contramap(f)
  }

  implicit def functorForModelMapperK[F[_]: Functor, S <: Scope, AA, BB]
    : Functor[ModelMapperK[F, S, AA, *]] =
    new Functor[ModelMapperK[F, S, AA, *]] {
      override def map[A, B](fa: ModelMapperK[F, S, AA, A])(f: A => B): ModelMapperK[F, S, AA, B] =
        fa.map(f)
    }

  implicit def applicativeForModelMapperK[F[_]: Applicative, S <: Scope, AA, BB]
    : Applicative[ModelMapperK[F, S, AA, *]] =
    new Applicative[ModelMapperK[F, S, AA, *]] {

      override def ap[A, B](
        ff: ModelMapperK[F, S, AA, A => B]
      )(fa: ModelMapperK[F, S, AA, A]): ModelMapperK[F, S, AA, B] =
        ModelMapperK.scoped[S](ff.mapper.ap(fa.mapper))

      override def map[A, B](fa: ModelMapperK[F, S, AA, A])(f: A => B): ModelMapperK[F, S, AA, B] =
        fa.map(f)

      override def pure[A](x: A): ModelMapperK[F, S, AA, A] =
        ModelMapperK.scoped[S].pure[F, AA, A](x)
    }
}
